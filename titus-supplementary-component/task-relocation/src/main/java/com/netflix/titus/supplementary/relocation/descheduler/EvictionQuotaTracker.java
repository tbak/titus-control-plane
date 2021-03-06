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

package com.netflix.titus.supplementary.relocation.descheduler;

import java.util.HashMap;
import java.util.Map;

import com.netflix.titus.api.eviction.model.EvictionQuota;
import com.netflix.titus.api.eviction.service.ReadOnlyEvictionOperations;
import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.model.reference.Reference;
import com.netflix.titus.runtime.connector.eviction.EvictionConfiguration;
import com.netflix.titus.runtime.connector.eviction.EvictionRejectionReasons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EvictionQuotaTracker {
    private static final Logger logger = LoggerFactory.getLogger(EvictionConfiguration.class);
    private final Map<String, Long> jobEvictionQuotas = new HashMap<>();
    private long systemEvictionQuota;
    private boolean systemDisruptionWindowOpen = true;

    EvictionQuotaTracker(ReadOnlyEvictionOperations evictionOperations, Map<String, Job<?>> jobs) {
        EvictionQuota systemEvictionQuotaObj = evictionOperations.getEvictionQuota(Reference.system());
        this.systemEvictionQuota = systemEvictionQuotaObj.getQuota();

        if (systemEvictionQuota == 0) {
            String evictionQuotaMessage = systemEvictionQuotaObj.getMessage();
            if (evictionQuotaMessage.equals(EvictionRejectionReasons.SYSTEM_WINDOW_CLOSED.getReasonMessage())) {
                systemDisruptionWindowOpen = false;
            }
        }
        logger.debug("System Eviction Quota {}. System disruption window open ? {}", systemEvictionQuota, systemDisruptionWindowOpen);

        jobs.forEach((id, job) -> {
                    long jobEvictionQuota = evictionOperations.findEvictionQuota(Reference.job(id)).map(EvictionQuota::getQuota).orElse(0L);
                    logger.debug("Job {} eviction quota {}", id, jobEvictionQuota);
                    jobEvictionQuotas.put(id, jobEvictionQuota);
                }
        );
    }

    long getSystemEvictionQuota() {
        return systemEvictionQuota;
    }

    boolean isSystemDisruptionWindowOpen() {
        return systemDisruptionWindowOpen;
    }

    long getJobEvictionQuota(String jobId) {
        return jobEvictionQuotas.getOrDefault(jobId, 0L);
    }

    void consumeQuota(String jobId, boolean isJobExemptFromSystemWindow) {
        if (systemEvictionQuota <= 0) {
            if (systemDisruptionWindowOpen || !isJobExemptFromSystemWindow) {
                throw DeschedulerException.noQuotaLeft("System quota is empty");
            }
        }
        if (!jobEvictionQuotas.containsKey(jobId)) {
            throw DeschedulerException.noQuotaLeft("Attempt to use quota for unknown job: jobId=%s", jobId);
        }
        long jobQuota = jobEvictionQuotas.get(jobId);
        if (jobQuota <= 0) {
            throw DeschedulerException.noQuotaLeft("Job quota is empty: jobId=%s", jobId);
        }
        systemEvictionQuota = systemEvictionQuota - 1;
        jobEvictionQuotas.put(jobId, jobQuota - 1);
    }

    /**
     * An alternative version to {@link #consumeQuota(String, boolean)} which does not throw an exception if there is not
     * enough quota to relocate a task of a given job. This is used when the immediate relocation is required.
     */
    void consumeQuotaNoError(String jobId) {
        if (systemEvictionQuota > 0) {
            systemEvictionQuota = systemEvictionQuota - 1;
        }
        long jobQuota = jobEvictionQuotas.getOrDefault(jobId, 0L);
        if (jobQuota > 0) {
            jobEvictionQuotas.put(jobId, jobQuota - 1);
        }
    }
}
