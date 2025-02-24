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

package org.apache.inlong.manager.service.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.pagehelper.PageInfo;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.inlong.manager.common.enums.Constant;
import org.apache.inlong.manager.common.enums.EntityStatus;
import org.apache.inlong.manager.common.enums.GroupState;
import org.apache.inlong.manager.common.enums.ProcessStatus;
import org.apache.inlong.manager.common.pojo.group.InlongGroupPulsarInfo;
import org.apache.inlong.manager.common.pojo.group.InlongGroupRequest;
import org.apache.inlong.manager.common.pojo.stream.InlongStreamFieldInfo;
import org.apache.inlong.manager.common.pojo.stream.InlongStreamInfo;
import org.apache.inlong.manager.common.pojo.workflow.ProcessResponse;
import org.apache.inlong.manager.common.pojo.workflow.TaskExecuteLogQuery;
import org.apache.inlong.manager.common.pojo.workflow.WorkflowResult;
import org.apache.inlong.manager.common.pojo.workflow.form.GroupResourceProcessForm;
import org.apache.inlong.manager.common.pojo.workflow.form.UpdateGroupProcessForm;
import org.apache.inlong.manager.common.pojo.workflow.form.UpdateGroupProcessForm.OperateType;
import org.apache.inlong.manager.dao.entity.WorkflowProcessEntity;
import org.apache.inlong.manager.dao.entity.WorkflowTaskEntity;
import org.apache.inlong.manager.dao.mapper.WorkflowProcessEntityMapper;
import org.apache.inlong.manager.dao.mapper.WorkflowTaskEntityMapper;
import org.apache.inlong.manager.service.ServiceBaseTest;
import org.apache.inlong.manager.service.core.InlongGroupService;
import org.apache.inlong.manager.service.core.InlongStreamService;
import org.apache.inlong.manager.service.mocks.MockDeleteSortListener;
import org.apache.inlong.manager.service.mocks.MockPlugin;
import org.apache.inlong.manager.service.mocks.MockRestartSortListener;
import org.apache.inlong.manager.service.mocks.MockStopSortListener;
import org.apache.inlong.manager.service.thirdparty.hive.CreateHiveTableListener;
import org.apache.inlong.manager.service.thirdparty.mq.CreatePulsarGroupTaskListener;
import org.apache.inlong.manager.service.thirdparty.mq.CreatePulsarResourceTaskListener;
import org.apache.inlong.manager.service.thirdparty.mq.CreateTubeGroupTaskListener;
import org.apache.inlong.manager.service.thirdparty.mq.CreateTubeTopicTaskListener;
import org.apache.inlong.manager.service.thirdparty.sort.PushSortConfigListener;
import org.apache.inlong.manager.workflow.WorkflowContext;
import org.apache.inlong.manager.workflow.core.WorkflowEngine;
import org.apache.inlong.manager.workflow.definition.ServiceTask;
import org.apache.inlong.manager.workflow.definition.WorkflowProcess;
import org.apache.inlong.manager.workflow.definition.WorkflowTask;
import org.apache.inlong.manager.workflow.event.ListenerResult;
import org.apache.inlong.manager.workflow.event.task.TaskEvent;
import org.apache.inlong.manager.workflow.event.task.TaskEventListener;
import org.apache.inlong.manager.workflow.util.WorkflowBeanUtils;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

@EnableAutoConfiguration
public class WorkflowServiceImplTest extends ServiceBaseTest {

    public static final String OPERATOR = "admin";

    public static final String STREAM_ID = "test_stream";

    public static final String DATA_ENCODING = "UTF-8";

    @Autowired
    protected WorkflowServiceImpl workflowService;
    @Autowired
    protected WorkflowEngine workflowEngine;
    @Autowired
    protected InlongGroupService groupService;
    @Autowired
    protected ServiceTaskListenerFactory taskListenerFactory;
    @Autowired
    protected WorkflowProcessEntityMapper processEntityMapper;
    @Autowired
    protected WorkflowTaskEntityMapper taskEntityMapper;
    @Autowired
    protected InlongStreamService streamService;

    protected ProcessName processName;

    protected String applicant;

    protected GroupResourceProcessForm form;

    public InlongGroupRequest initGroupForm(String middlewareType) {
        processName = ProcessName.CREATE_GROUP_RESOURCE;
        applicant = OPERATOR;
        form = new GroupResourceProcessForm();
        InlongGroupRequest groupInfo = new InlongGroupRequest();
        form.setInlongStreamId(STREAM_ID);
        form.setGroupInfo(groupInfo);
        groupInfo.setName("test");
        groupInfo.setInCharges(OPERATOR);
        groupInfo.setInlongGroupId("b_test");
        groupInfo.setMiddlewareType(middlewareType);
        groupInfo.setMqExtInfo(new InlongGroupPulsarInfo());
        groupInfo.setMqResourceObj("test-queue");
        groupService.save(groupInfo, OPERATOR);
        groupInfo.setStatus(GroupState.GROUP_WAIT_APPROVAL.getCode());
        groupService.update(groupInfo, OPERATOR);
        groupInfo.setStatus(GroupState.GROUP_APPROVE_PASSED.getCode());
        groupService.update(groupInfo, OPERATOR);
        groupInfo.setStatus(GroupState.GROUP_CONFIG_ING.getCode());
        groupService.update(groupInfo, OPERATOR);
        return groupInfo;
    }

    public InlongStreamInfo createStreamInfo(InlongGroupRequest inlongGroupRequest) {
        InlongStreamInfo streamInfo = new InlongStreamInfo();
        streamInfo.setInlongGroupId(inlongGroupRequest.getInlongGroupId());
        streamInfo.setInlongStreamId(STREAM_ID);
        streamInfo.setMqResourceObj(STREAM_ID);
        streamInfo.setDataSeparator("124");
        streamInfo.setDataEncoding(DATA_ENCODING);
        streamInfo.setInCharges(OPERATOR);
        streamInfo.setCreator(OPERATOR);
        streamInfo.setFieldList(createStreamFields(inlongGroupRequest.getInlongGroupId(), STREAM_ID));
        streamService.save(streamInfo, OPERATOR);
        return streamInfo;
    }

    public List<InlongStreamFieldInfo> createStreamFields(String inlongGroupId, String inlongStreamId) {
        final List<InlongStreamFieldInfo> streamFieldInfos = new ArrayList<>();
        InlongStreamFieldInfo fieldInfo = new InlongStreamFieldInfo();
        fieldInfo.setFieldName("id");
        fieldInfo.setFieldType("int");
        fieldInfo.setFieldComment("idx");
        streamFieldInfos.add(fieldInfo);
        return streamFieldInfos;
    }

    /**
     * Mock the task listener factory
     */
    public void mockTaskListenerFactory() {
        CreateTubeGroupTaskListener createTubeGroupTaskListener = mock(CreateTubeGroupTaskListener.class);
        when(createTubeGroupTaskListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(createTubeGroupTaskListener.name()).thenReturn(CreateHiveTableListener.class.getSimpleName());
        when(createTubeGroupTaskListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setCreateTubeGroupTaskListener(createTubeGroupTaskListener);

        CreateTubeTopicTaskListener createTubeTopicTaskListener = mock(CreateTubeTopicTaskListener.class);
        when(createTubeTopicTaskListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(createTubeTopicTaskListener.name()).thenReturn(CreateTubeTopicTaskListener.class.getSimpleName());
        when(createTubeTopicTaskListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setCreateTubeTopicTaskListener(createTubeTopicTaskListener);

        CreatePulsarResourceTaskListener createPulsarResourceTaskListener = mock(
                CreatePulsarResourceTaskListener.class);
        when(createPulsarResourceTaskListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(createPulsarResourceTaskListener.name()).thenReturn(
                CreatePulsarResourceTaskListener.class.getSimpleName());
        when(createPulsarResourceTaskListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setCreatePulsarResourceTaskListener(createPulsarResourceTaskListener);

        CreatePulsarGroupTaskListener createPulsarGroupTaskListener = mock(CreatePulsarGroupTaskListener.class);
        when(createPulsarGroupTaskListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(createPulsarGroupTaskListener.name()).thenReturn(CreatePulsarGroupTaskListener.class.getSimpleName());
        when(createPulsarGroupTaskListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setCreatePulsarGroupTaskListener(createPulsarGroupTaskListener);

        CreateHiveTableListener createHiveTableListener = mock(CreateHiveTableListener.class);
        when(createHiveTableListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(createHiveTableListener.name()).thenReturn(CreateHiveTableListener.class.getSimpleName());
        when(createHiveTableListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setCreateHiveTableListener(createHiveTableListener);

        PushSortConfigListener pushSortConfigListener = mock(PushSortConfigListener.class);
        when(pushSortConfigListener.listen(any(WorkflowContext.class))).thenReturn(ListenerResult.success());
        when(pushSortConfigListener.name()).thenReturn(PushSortConfigListener.class.getSimpleName());
        when(pushSortConfigListener.event()).thenReturn(TaskEvent.COMPLETE);
        taskListenerFactory.setPushSortConfigListener(pushSortConfigListener);
        taskListenerFactory.clearListeners();
        taskListenerFactory.init();
    }

    @Test
    public void testStartCreatePulsarWorkflow() {
        initGroupForm(Constant.MIDDLEWARE_PULSAR);
        mockTaskListenerFactory();
        WorkflowContext context = workflowEngine.processService().start(processName.name(), applicant, form);
        WorkflowResult result = WorkflowBeanUtils.result(context);
        ProcessResponse view = result.getProcessInfo();
        Assert.assertSame(view.getStatus(), ProcessStatus.COMPLETED);
        WorkflowProcess process = context.getProcess();
        WorkflowTask task = process.getTaskByName("initMQ");
        Assert.assertTrue(task instanceof ServiceTask);
        Assert.assertEquals(2, task.getNameToListenerMap().size());

        List<TaskEventListener> listeners = Lists.newArrayList(task.getNameToListenerMap().values());
        Assert.assertTrue(listeners.get(0) instanceof CreatePulsarGroupTaskListener);
        Assert.assertTrue(listeners.get(1) instanceof CreatePulsarResourceTaskListener);
    }

    @Test
    public void testStartCreateTubeWorkflow() {
        initGroupForm(Constant.MIDDLEWARE_TUBE);
        mockTaskListenerFactory();
        WorkflowContext context = workflowEngine.processService().start(processName.name(), applicant, form);
        WorkflowResult result = WorkflowBeanUtils.result(context);
        ProcessResponse response = result.getProcessInfo();
        Assert.assertSame(response.getStatus(), ProcessStatus.COMPLETED);

        WorkflowProcess process = context.getProcess();
        WorkflowTask task = process.getTaskByName("initMQ");
        Assert.assertTrue(task instanceof ServiceTask);
        Assert.assertEquals(2, task.getNameToListenerMap().size());

        List<TaskEventListener> listeners = Lists.newArrayList(task.getNameToListenerMap().values());
        Assert.assertTrue(listeners.get(0) instanceof CreateTubeTopicTaskListener);
        Assert.assertTrue(listeners.get(1) instanceof CreateTubeGroupTaskListener);
    }

    @Test
    public void testSuspendProcess() {
        InlongGroupRequest groupInfo = initGroupForm(Constant.MIDDLEWARE_PULSAR);
        groupInfo.setStatus(EntityStatus.GROUP_CONFIG_SUCCESSFUL.getCode());
        groupService.update(groupInfo, OPERATOR);
        UpdateGroupProcessForm form = new UpdateGroupProcessForm();
        form.setGroupInfo(groupInfo);
        form.setOperateType(OperateType.SUSPEND);
        taskListenerFactory.acceptPlugin(new MockPlugin());

        WorkflowContext context = workflowEngine.processService()
                .start(ProcessName.SUSPEND_GROUP_PROCESS.name(), applicant, form);
        WorkflowResult result = WorkflowBeanUtils.result(context);
        ProcessResponse response = result.getProcessInfo();
        Assert.assertSame(response.getStatus(), ProcessStatus.COMPLETED);

        WorkflowProcess process = context.getProcess();
        WorkflowTask stopSortTask = process.getTaskByName("stopSort");
        Assert.assertTrue(stopSortTask instanceof ServiceTask);
        List<TaskEventListener> listeners = Lists.newArrayList(stopSortTask.getNameToListenerMap().values());
        Assert.assertTrue(listeners.get(0) instanceof MockStopSortListener);

        WorkflowTask stopSourceTask = process.getTaskByName("stopSource");
        Assert.assertTrue(stopSourceTask instanceof ServiceTask);
        listeners = Lists.newArrayList(stopSourceTask.getNameToListenerMap().values());
    }

    @Test
    public void testRestartProcess() {
        InlongGroupRequest groupInfo = initGroupForm(Constant.MIDDLEWARE_PULSAR);
        groupInfo.setStatus(GroupState.GROUP_CONFIG_SUCCESSFUL.getCode());
        groupService.update(groupInfo, OPERATOR);
        groupInfo.setStatus(GroupState.GROUP_SUSPEND.getCode());
        groupService.update(groupInfo, OPERATOR);
        UpdateGroupProcessForm form = new UpdateGroupProcessForm();
        form.setGroupInfo(groupInfo);
        form.setOperateType(OperateType.RESTART);
        taskListenerFactory.acceptPlugin(new MockPlugin());

        WorkflowContext context = workflowEngine.processService()
                .start(ProcessName.RESTART_GROUP_PROCESS.name(), applicant, form);
        WorkflowResult result = WorkflowBeanUtils.result(context);
        ProcessResponse response = result.getProcessInfo();
        Assert.assertSame(response.getStatus(), ProcessStatus.COMPLETED);

        WorkflowProcess process = context.getProcess();
        WorkflowTask restartSort = process.getTaskByName("restartSort");
        Assert.assertTrue(restartSort instanceof ServiceTask);
        List<TaskEventListener> listeners = Lists.newArrayList(restartSort.getNameToListenerMap().values());
        Assert.assertEquals(1, listeners.size());
        Assert.assertTrue(listeners.get(0) instanceof MockRestartSortListener);

        WorkflowTask restartSourceTask = process.getTaskByName("restartSource");
        Assert.assertTrue(restartSourceTask instanceof ServiceTask);
        listeners = Lists.newArrayList(restartSourceTask.getNameToListenerMap().values());
        Assert.assertEquals(2, listeners.size());
    }

    @Test
    public void testStopProcess() {
        InlongGroupRequest groupInfo = initGroupForm(Constant.MIDDLEWARE_PULSAR);
        groupInfo.setStatus(GroupState.GROUP_CONFIG_SUCCESSFUL.getCode());
        groupService.update(groupInfo, OPERATOR);
        groupInfo.setStatus(GroupState.GROUP_SUSPEND.getCode());
        groupService.update(groupInfo, OPERATOR);
        UpdateGroupProcessForm form = new UpdateGroupProcessForm();
        form.setGroupInfo(groupInfo);
        form.setOperateType(OperateType.DELETE);
        taskListenerFactory.acceptPlugin(new MockPlugin());

        WorkflowContext context = workflowEngine.processService()
                .start(ProcessName.DELETE_GROUP_PROCESS.name(), applicant, form);
        WorkflowResult result = WorkflowBeanUtils.result(context);
        ProcessResponse view = result.getProcessInfo();
        Assert.assertSame(view.getStatus(), ProcessStatus.COMPLETED);
        WorkflowProcess process = context.getProcess();
        WorkflowTask deleteSort = process.getTaskByName("deleteSort");
        Assert.assertTrue(deleteSort instanceof ServiceTask);
        List<TaskEventListener> listeners = Lists.newArrayList(deleteSort.getNameToListenerMap().values());
        Assert.assertEquals(1, listeners.size());
        Assert.assertTrue(listeners.get(0) instanceof MockDeleteSortListener);

        WorkflowTask deleteSourceTask = process.getTaskByName("deleteSource");
        Assert.assertTrue(deleteSourceTask instanceof ServiceTask);
        listeners = Lists.newArrayList(deleteSourceTask.getNameToListenerMap().values());
        Assert.assertEquals(2, listeners.size());
    }

    @Test
    public void testListTaskExecuteLogs() {
        // insert process instance
        String groupId = "test_group";
        WorkflowProcessEntity process = new WorkflowProcessEntity();
        process.setId(1);
        process.setInlongGroupId(groupId);
        process.setName("CREATE_GROUP_RESOURCE");
        process.setHidden(1);
        process.setStatus(ProcessStatus.COMPLETED.name());
        processEntityMapper.insert(process);

        // insert task instance
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(1);
        task.setType("ServiceTask");
        task.setProcessId(1);
        taskEntityMapper.insert(task);

        // query execute logs
        TaskExecuteLogQuery query = new TaskExecuteLogQuery();
        query.setInlongGroupId(groupId);
        query.setProcessNames(Collections.singletonList("CREATE_GROUP_RESOURCE"));
        PageInfo<WorkflowExecuteLog> logPageInfo = workflowService.listTaskExecuteLogs(query);

        Assert.assertEquals(1, logPageInfo.getTotal());
    }

}
