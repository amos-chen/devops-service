package io.choerodon.devops.app.service;

import io.choerodon.devops.app.eventhandler.payload.HarborPayload;
import io.choerodon.devops.infra.dto.DevopsProjectDTO;
import io.choerodon.devops.infra.dto.HarborUserDTO;
import io.choerodon.devops.infra.dto.harbor.User;
import io.choerodon.devops.infra.dto.iam.ProjectDTO;
import io.choerodon.devops.infra.feign.HarborClient;

import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: Runge
 * Date: 2018/4/8
 * Time: 10:30
 * Description:
 */
public interface HarborService {

    void createHarborForProject(HarborPayload harborPayload);

    void createHarbor(HarborClient harborClient, Long projectId, String projectCode, Boolean createUser, Boolean harborPrivate);

    void createHarborUser(HarborPayload harborPayload, User user, ProjectDTO projectDTO, List<Integer> roles);

    User convertUser(ProjectDTO projectDTO, Boolean isPush,String username);
}
