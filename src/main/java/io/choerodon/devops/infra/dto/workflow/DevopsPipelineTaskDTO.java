package io.choerodon.devops.infra.dto.workflow;

import java.util.List;


/**
 * Created by Sheep on 2019/4/2.
 */
public class DevopsPipelineTaskDTO {
    private Long taskRecordId;
    private String taskName;
    /**
     * 审核人员 userIds
     */
    private List<String> usernames;
    private String taskType;
    /**
     * 是否多人审核 审核人员>1 为true
     */
    private Boolean multiAssign;

    /**
     * 是否会签
     */
    private Long sign;

    public Long getTaskRecordId() {
        return taskRecordId;
    }

    public void setTaskRecordId(Long taskRecordId) {
        this.taskRecordId = taskRecordId;
    }

    public Long getSign() {
        return sign;
    }

    public void setSign(Long sign) {
        this.sign = sign;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Boolean getMultiAssign() {
        return multiAssign;
    }

    public void setMultiAssign(Boolean multiAssign) {
        this.multiAssign = multiAssign;
    }
}
