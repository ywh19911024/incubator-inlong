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

package org.apache.inlong.agent.plugin.sources.snapshot;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BinlogSnapshotBase implements SnapshotBase {

    public static final int BUFFER_SIZE = 1024;
    public static final int START_OFFSET = 0;
    private static final Logger log = LoggerFactory.getLogger(BinlogSnapshotBase.class);
    private File file;

    private byte[] offset;

    public BinlogSnapshotBase(String filePath) {
        file = new File(filePath);
    }

    @Override
    public String getSnapshot() {
        load();
        return new String(offset, StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() {
    }

    public void load() {
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
            FileInputStream fis = new FileInputStream(file);
            BufferedInputStream inputStream = new BufferedInputStream(fis);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int len;
            byte[] buf = new byte[BUFFER_SIZE];
            while ((len = inputStream.read(buf)) != -1) {
                outputStream.write(buf, START_OFFSET, len);
            }
            offset = outputStream.toByteArray();
            inputStream.close();
            outputStream.close();
        } catch (Exception ex) {
            log.error("load binlog offset error", ex);
        }
    }

    public void save(String snapshot) {
        byte[] bytes = snapshot.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length != 0) {
            offset = bytes;
            try (OutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
            } catch (Exception e) {
                log.error("save offset to file error", e);
            }
        }
    }

}
