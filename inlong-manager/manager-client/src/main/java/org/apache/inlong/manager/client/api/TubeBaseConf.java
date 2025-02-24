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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ApiModel("Base configuration for Tube")
public class TubeBaseConf extends MqBaseConf {

    @ApiModelProperty("Message queue type")
    private MqType type = MqType.TUBE;

    @ApiModelProperty("Tube manager URL")
    private String tubeManagerUrl;

    @ApiModelProperty("Tube master URL")
    private String tubeMasterUrl;

    @ApiModelProperty("Tube Cluster Id")
    private int tubeClusterId = 1;

    @ApiModelProperty("GroupName for tube producer")
    private String groupName;
}
