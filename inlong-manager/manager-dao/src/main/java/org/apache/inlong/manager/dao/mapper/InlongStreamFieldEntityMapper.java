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

package org.apache.inlong.manager.dao.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.inlong.manager.dao.entity.InlongStreamFieldEntity;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InlongStreamFieldEntityMapper {

    int deleteByPrimaryKey(Integer id);

    int insert(InlongStreamFieldEntity record);

    int insertSelective(InlongStreamFieldEntity record);

    InlongStreamFieldEntity selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(InlongStreamFieldEntity record);

    int updateByPrimaryKey(InlongStreamFieldEntity record);

    List<InlongStreamFieldEntity> selectByIdentifier(@Param("groupId") String groupId,
            @Param("streamId") String streamId);

    int insertAll(@Param("fieldList") List<InlongStreamFieldEntity> fieldEntityList);

    List<InlongStreamFieldEntity> selectStreamFields(@Param("groupId") String groupId,
            @Param("streamId") String streamId);

    /**
     * According to the inlong group id and inlong stream id, physically delete all fields
     */
    int deleteAllByIdentifier(@Param("groupId") String groupId, @Param("streamId") String streamId);

    /**
     * According to the inlong group id and inlong stream id, logically delete all fields
     */
    int logicDeleteAllByIdentifier(@Param("groupId") String groupId, @Param("streamId") String streamId);

}