/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.client.api;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.Serializable;
import lombok.Data;

@Data
@ApiModel("Base configuration for message queue")
public abstract class MqBaseConf implements Serializable {

    public static final MqBaseConf BLANK_MQ_CONF = new MqBaseConf() {
        @Override
        public MqType getType() {
            return MqType.NONE;
        }
    };

    public enum MqType {
        PULSAR,
        TUBE,
        NONE;

        public static MqType forType(String type) {
            for (MqType mqType : values()) {
                if (mqType.name().equals(type)) {
                    return mqType;
                }
            }
            throw new IllegalArgumentException(String.format("Unsupport queue=%s for Inlong", type));
        }
    }

    @ApiModelProperty("The number of partitions of Topic, 1-20")
    private int topicPartitionNum = 3;

    public abstract MqType getType();
}
