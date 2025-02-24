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

package org.apache.inlong.manager.client.api.auth;

import com.alibaba.fastjson.JSONObject;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@NoArgsConstructor
public class SecretTokenAuthentication extends SecretAuthentication {

    public static final String SECRET_TOKEN = "secret_token";

    @Getter
    protected String sToken;

    public SecretTokenAuthentication(String secretId, String secretKey, String secretToken) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.sToken = secretToken;
    }

    @Override
    public AuthType getAuthType() {
        return AuthType.SECRET_AND_TOKEN;
    }

    @Override
    public void configure(Map<String, String> properties) {
        super.configure(properties);
        this.sToken = properties.get(SECRET_TOKEN);
    }

    @SneakyThrows
    @Override
    public String toString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(SECRET_ID, this.getSecretId());
        jsonObject.put(SECRET_KEY, this.getSecretKey());
        jsonObject.put(SECRET_TOKEN, this.getSToken());
        return jsonObject.toString();
    }

}
