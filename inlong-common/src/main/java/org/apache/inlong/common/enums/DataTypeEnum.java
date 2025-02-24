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

package org.apache.inlong.common.enums;

import java.util.Locale;
import lombok.Getter;

public enum DataTypeEnum {
    CSV("csv"),
    AVRO("avro"),
    JSON("json"),
    CANAL("canal"),
    DEBEZIUM_JSON("debezium_json");

    @Getter
    private String name;

    DataTypeEnum(String name) {
        this.name = name;
    }

    public static DataTypeEnum forName(String name) {
        for (DataTypeEnum dataType : values()) {
            if (dataType.getName().equals(name.toLowerCase(Locale.ROOT))) {
                return dataType;
            }
        }
        throw new IllegalArgumentException(String.format("Unsupport dataType for Inlong:%s", name));
    }
}
