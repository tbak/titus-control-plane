/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.titus.testkit.model.job;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.netflix.titus.api.jobmanager.TaskAttributes;
import com.netflix.titus.api.jobmanager.model.job.JobFunctions;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.api.jobmanager.model.job.vpc.IpAddressAllocation;
import com.netflix.titus.api.jobmanager.model.job.vpc.IpAddressLocation;
import com.netflix.titus.api.jobmanager.model.job.vpc.SignedIpAddressAllocation;
import com.netflix.titus.common.data.generator.DataGenerator;
import com.netflix.titus.testkit.model.PrimitiveValueGenerators;

import static com.netflix.titus.common.data.generator.DataGenerator.items;

public final class JobIpAllocationGenerator {
    private JobIpAllocationGenerator() {
    }

    private static DataGenerator<String> zones() { return items("zoneA", "zoneB").loop(); }

    private static DataGenerator<String> subnets() { return items("subnet-1").loop(); }

    private static DataGenerator<String> regions() { return items("us-east-1").loop(); }

    private static DataGenerator<String> ipAddresses() {
        return PrimitiveValueGenerators.ipv4CIDRs("96.96.96.1/28");
    }

    private static DataGenerator<IpAddressLocation> ipAddressLocations() {
        return DataGenerator.union(
                regions(),
                subnets(),
                zones(),
                (region, subnet, zone) -> IpAddressLocation.newBuilder()
                        .withRegion(region)
                        .withSubnetId(subnet)
                        .withAvailabilityZone(zone)
                        .build()
        );
    }

    public static DataGenerator<SignedIpAddressAllocation> jobIpAllocations(int count) {
        List<String> ipAddressList = ipAddresses().getValues(count);
        List<IpAddressLocation> ipAddressLocationList = ipAddressLocations().getValues(count);
        List<SignedIpAddressAllocation> signedIpAddressAllocationList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            signedIpAddressAllocationList.add(SignedIpAddressAllocation.newBuilder()
                    .withIpAddressAllocation(IpAddressAllocation.newBuilder()
                            .withUuid(UUID.randomUUID().toString())
                            .withIpAddress(ipAddressList.get(i))
                            .withIpAddressLocation(ipAddressLocationList.get(i))
                            .build()
                    )
                    .withAuthoritativePublicKey(new byte[0])
                    .withHostPublicKey(new byte[0])
                    .withHostPublicKeySignature(new byte[0])
                    .withMessage(new byte[0])
                    .withMessageSignature(new byte[0])
                    .build());
        }
        return DataGenerator.items(signedIpAddressAllocationList);
    }

    public static Task appendIpAllocationAttribute(Task task, String ipAllocationId) {
        return JobFunctions.appendTaskContext(task, TaskAttributes.TASK_ATTRIBUTES_IP_ALLOCATION_ID, ipAllocationId);
    }
}
