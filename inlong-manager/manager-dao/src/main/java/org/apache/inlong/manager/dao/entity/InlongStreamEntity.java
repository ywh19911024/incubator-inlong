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

package org.apache.inlong.manager.dao.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class InlongStreamEntity implements Serializable {

    private static final long serialVersionUID = 1L;
    private Integer id;
    private String inlongStreamId;
    private String inlongGroupId;
    private String name;
    private String description;
    private String mqResourceObj;
    private String dataSourceType;
    private Integer storagePeriod;
    private String dataType;
    private String dataEncoding;
    private String dataSeparator;
    private String dataEscapeChar;
    private Integer havePredefinedFields;

    private Integer dailyRecords;
    private Integer dailyStorage;
    private Integer peakRecords;
    private Integer maxLength;
    private String inCharges;

    private Integer status;
    private Integer previousStatus;
    private Integer isDeleted;
    private String creator;
    private String modifier;
    private Date createTime;
    private Date modifyTime;
    private String tempView;

}