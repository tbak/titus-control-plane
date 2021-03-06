/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.testkit.perf.load.runner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;

import com.netflix.titus.api.jobmanager.model.job.JobDescriptor;
import com.netflix.titus.api.jobmanager.model.job.JobFunctions;
import com.netflix.titus.api.jobmanager.model.job.JobGroupInfo;
import com.netflix.titus.api.jobmanager.model.job.ext.BatchJobExt;
import com.netflix.titus.api.jobmanager.model.job.ext.ServiceJobExt;
import com.netflix.titus.common.util.CollectionsExt;
import com.netflix.titus.common.util.rx.ObservableExt;
import com.netflix.titus.common.util.rx.ReactorExt;
import com.netflix.titus.common.util.rx.RetryHandlerBuilder;
import com.netflix.titus.testkit.perf.load.ExecutionContext;
import com.netflix.titus.testkit.perf.load.plan.ExecutionPlan;
import com.netflix.titus.testkit.perf.load.plan.JobExecutableGenerator;
import com.netflix.titus.testkit.perf.load.runner.job.BatchJobExecutor;
import com.netflix.titus.testkit.perf.load.runner.job.JobExecutor;
import com.netflix.titus.testkit.perf.load.runner.job.ServiceJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import rx.Completable;
import rx.Subscription;
import rx.schedulers.Schedulers;

public class ScenarioRunner {

    private static final Logger logger = LoggerFactory.getLogger(Orchestrator.class);

    private final Disposable jobScenarioSubscription;
    private final Subscription agentScenarioSubscription;

    private final AtomicInteger nextSequenceId = new AtomicInteger();
    private final String scenarioExecutionId;
    private final Map<String, Object> requestContext;

    public ScenarioRunner(String scenarioExecutionId,
                          Map<String, Object> requestContext,
                          JobExecutableGenerator jobExecutableGenerator,
                          List<ExecutionPlan> agentExecutionPlans,
                          ExecutionContext context) {
        this.scenarioExecutionId = scenarioExecutionId;
        this.requestContext = requestContext;
        this.agentScenarioSubscription = startAgentExecutionScenario(agentExecutionPlans, context).subscribe(
                () -> logger.info("Agent scenario subscription completed"),
                e -> logger.error("Agent scenario subscription terminated with an error", e)
        );
        this.jobScenarioSubscription = startJobExecutionScenario(jobExecutableGenerator, context).subscribe(
                ignored -> logger.info("Job scenario subscription completed"),
                e -> logger.error("Job scenario subscription terminated with an error", e)
        );
    }

    @PreDestroy
    public void shutdown() {
        ObservableExt.safeUnsubscribe(agentScenarioSubscription);
        ReactorExt.safeDispose(jobScenarioSubscription);
    }

    public String getScenarioExecutionId() {
        return scenarioExecutionId;
    }

    public Map<String, Object> getRequestContext() {
        return requestContext;
    }

    private Completable startAgentExecutionScenario(List<ExecutionPlan> agentExecutionPlans, ExecutionContext context) {
        if (agentExecutionPlans.isEmpty()) {
            return Completable.complete();
        }

        List<AgentExecutionPlanRunner> runners = agentExecutionPlans.stream()
                .map(plan -> new AgentExecutionPlanRunner(plan, context, Schedulers.computation()))
                .collect(Collectors.toList());

        List<Completable> actions = runners.stream().map(AgentExecutionPlanRunner::awaitJobCompletion).collect(Collectors.toList());

        return Completable.merge(actions)
                .doOnSubscribe(subscription -> runners.forEach(AgentExecutionPlanRunner::start))
                .doOnUnsubscribe(() -> runners.forEach(AgentExecutionPlanRunner::stop));
    }

    private Mono<Void> startJobExecutionScenario(JobExecutableGenerator jobExecutableGenerator, ExecutionContext context) {
        return jobExecutableGenerator.executionPlans()
                .flatMap(executable -> {
                    JobDescriptor<?> jobSpec = tagged(newJobDescriptor(executable));
                    Mono<? extends JobExecutor> jobSubmission = JobFunctions.isBatchJob(jobSpec)
                            ? BatchJobExecutor.submitJob((JobDescriptor<BatchJobExt>) jobSpec, context)
                            : ServiceJobExecutor.submitJob((JobDescriptor<ServiceJobExt>) jobSpec, context);

                    return jobSubmission
                            .retryWhen(RetryHandlerBuilder.retryHandler()
                                    .withUnlimitedRetries()
                                    .withDelay(1_000, 30_000, TimeUnit.MILLISECONDS)
                                    .withReactorScheduler(reactor.core.scheduler.Schedulers.parallel())
                                    .buildRetryExponentialBackoff()
                            )
                            .flatMap(executor -> {
                                        JobExecutionPlanRunner runner = new JobExecutionPlanRunner(executor, executable.getJobExecutionPlan(), context, Schedulers.computation());
                                        return runner.awaitJobCompletion()
                                                .doOnSubscribe(subscription -> runner.start())
                                                .doOnTerminate(() -> {
                                                    logger.info("Creating new replacement job...");
                                                    runner.stop();
                                                    jobExecutableGenerator.completed(executable);
                                                });
                                    }
                            );
                }).then();
    }

    private JobDescriptor<?> newJobDescriptor(JobExecutableGenerator.Executable executable) {
        JobDescriptor<?> jobDescriptor = executable.getJobSpec();
        if (JobFunctions.isBatchJob(jobDescriptor)) {
            return jobDescriptor;
        }
        JobGroupInfo jobGroupInfo = jobDescriptor.getJobGroupInfo();
        String seq = jobGroupInfo.getSequence() + nextSequenceId.getAndIncrement();
        return jobDescriptor.toBuilder().withJobGroupInfo(
                jobGroupInfo.toBuilder().withSequence(seq).build()
        ).build();
    }

    private JobDescriptor<?> tagged(JobDescriptor<?> jobSpec) {
        return jobSpec.toBuilder().withAttributes(
                CollectionsExt.copyAndAdd(jobSpec.getAttributes(), ExecutionContext.LABEL_SESSION, scenarioExecutionId)
        ).build();
    }
}
