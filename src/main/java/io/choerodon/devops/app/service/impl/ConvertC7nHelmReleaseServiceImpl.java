package io.choerodon.devops.app.service.impl;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.choerodon.devops.api.vo.kubernetes.C7nHelmRelease;
import io.choerodon.devops.app.service.AppServiceInstanceService;
import io.choerodon.devops.app.service.DevopsEnvFileResourceService;
import io.choerodon.devops.app.service.DevopsEnvironmentService;
import io.choerodon.devops.infra.dto.AppServiceInstanceDTO;
import io.choerodon.devops.infra.dto.DevopsEnvFileResourceDTO;
import io.choerodon.devops.infra.dto.DevopsEnvironmentDTO;
import io.choerodon.devops.infra.enums.GitOpsObjectError;
import io.choerodon.devops.infra.enums.ResourceType;
import io.choerodon.devops.infra.exception.GitOpsExplainException;
import io.choerodon.devops.infra.mapper.AppServiceInstanceMapper;
import io.choerodon.devops.infra.util.GitOpsUtil;
import io.choerodon.devops.infra.util.TypeUtil;

@Component
public class ConvertC7nHelmReleaseServiceImpl extends ConvertK8sObjectService<C7nHelmRelease> {
    @Autowired
    private AppServiceInstanceService appServiceInstanceService;
    @Autowired
    private AppServiceInstanceMapper appServiceInstanceMapper;
    @Autowired
    private DevopsEnvironmentService devopsEnvironmentService;
    @Autowired
    private DevopsEnvFileResourceService devopsEnvFileResourceService;

    public ConvertC7nHelmReleaseServiceImpl() {
        super(C7nHelmRelease.class);
    }

    @Override
    public void checkParameters(C7nHelmRelease c7nHelmRelease, Map<String, String> objectPath) {
        String filePath = objectPath.get(TypeUtil.objToString(c7nHelmRelease.hashCode()));
        if (c7nHelmRelease.getMetadata() == null) {
            throw new GitOpsExplainException(GitOpsObjectError.RELEASE_META_DATA_NOT_FOUND.getError(), filePath);
        } else {
            if (c7nHelmRelease.getMetadata().getName() == null) {
                throw new GitOpsExplainException(GitOpsObjectError.RELEASE_NAME_NOT_FOUND.getError(), filePath);
            }
        }
        if (c7nHelmRelease.getSpec() == null) {
            throw new GitOpsExplainException(GitOpsObjectError.RELEASE_SPEC_NOT_FOUND.getError(), filePath);
        } else {
            if (c7nHelmRelease.getSpec().getChartName() == null) {
                throw new GitOpsExplainException(GitOpsObjectError.RELEASE_CHART_NAME_NOT_FOUND.getError(), filePath);
            }
            if (c7nHelmRelease.getSpec().getChartVersion() == null) {
                throw new GitOpsExplainException(GitOpsObjectError.RELEASE_CHART_VERSION_NOT_FOUND.getError(), filePath);
            }
            if (c7nHelmRelease.getSpec().getRepoUrl() == null) {
                throw new GitOpsExplainException(GitOpsObjectError.RELEASE_REPO_URL_NOT_FOUND.getError(), filePath);
            }
        }
        if (c7nHelmRelease.getApiVersion() == null) {
            throw new GitOpsExplainException(GitOpsObjectError.RELEASE_API_VERSION_NOT_FOUND.getError(), filePath);
        }

    }

    @Override
    public void checkIfExist(List<C7nHelmRelease> c7nHelmReleases, Long envId, List<DevopsEnvFileResourceDTO> beforeSyncDelete, Map<String, String> objectPath, C7nHelmRelease c7nHelmRelease) {
        String filePath = objectPath.get(TypeUtil.objToString(c7nHelmRelease.hashCode()));
        String instanceCode = c7nHelmRelease.getMetadata().getName();
        AppServiceInstanceDTO appServiceInstanceDTO = appServiceInstanceService.baseQueryByCodeAndEnv(instanceCode, envId);
        DevopsEnvironmentDTO devopsEnvironmentDTO = devopsEnvironmentService.baseQueryById(envId);
        String chartName = c7nHelmRelease.getSpec().getChartName();

        if (appServiceInstanceDTO != null) {
            Long instanceId = appServiceInstanceDTO.getId();
            if (beforeSyncDelete.stream()
                    .filter(devopsEnvFileResourceDTO -> devopsEnvFileResourceDTO.getResourceType()
                            .equals(c7nHelmRelease.getKind()))
                    .noneMatch(devopsEnvFileResourceDTO ->
                            devopsEnvFileResourceDTO.getResourceId()
                                    .equals(instanceId))) {
                DevopsEnvFileResourceDTO devopsEnvFileResourceDTO = devopsEnvFileResourceService.baseQueryByEnvIdAndResourceId(envId, instanceId, c7nHelmRelease.getKind());
                if (devopsEnvFileResourceDTO != null && !devopsEnvFileResourceDTO.getFilePath().equals(objectPath.get(TypeUtil.objToString(c7nHelmRelease.hashCode())))) {
                    throw new GitOpsExplainException(GitOpsObjectError.OBJECT_EXIST.getError(), filePath, instanceCode);
                }

                // 一个集群环境只允许安装一个组件chart的一个实例
                if (GitOpsUtil.isClusterComponent(devopsEnvironmentDTO.getType(), c7nHelmRelease)
                        && appServiceInstanceMapper.isComponentDeployed(envId, chartName)) {
                    throw new GitOpsExplainException(GitOpsObjectError.DUPLICATED_CLUSTER_COMPONENT.getError(), filePath, c7nHelmRelease.getSpec().getChartName());
                }
            }
        }
        if (c7nHelmReleases.stream()
                .anyMatch(c7nHelmRelease1 -> c7nHelmRelease1.getMetadata().getName()
                        .equals(instanceCode))) {
            throw new GitOpsExplainException(GitOpsObjectError.OBJECT_EXIST.getError(), filePath, instanceCode);
        } else if (GitOpsUtil.isClusterComponent(devopsEnvironmentDTO.getType(), c7nHelmRelease)
                && c7nHelmReleases.stream().anyMatch(release -> release.getSpec().getChartName().equals(chartName))) {
            // 一个集群环境只允许安装一个组件chart的一个实例
            throw new GitOpsExplainException(
                    GitOpsObjectError.DUPLICATED_CLUSTER_COMPONENT.getError(),
                    filePath, c7nHelmRelease.getSpec().getChartName());
        } else {
            c7nHelmReleases.add(c7nHelmRelease);
        }
    }

    @Override
    public ResourceType getType() {
        return ResourceType.C7NHELMRELEASE;
    }
}
