package io.choerodon.devops.app.service.impl;

import java.sql.Timestamp;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.infra.dto.*;
import io.choerodon.devops.infra.dto.iam.IamUserDTO;
import io.choerodon.devops.infra.feign.operator.IamServiceClientOperator;
import io.kubernetes.client.JSON;
import io.kubernetes.client.models.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.choerodon.core.exception.CommonException;
import io.choerodon.devops.api.vo.*;
import io.choerodon.devops.infra.enums.ObjectType;
import io.choerodon.devops.infra.enums.ResourceType;
import io.choerodon.devops.infra.mapper.DevopsEnvResourceMapper;
import io.choerodon.devops.infra.util.K8sUtil;
import io.choerodon.devops.infra.util.TypeUtil;

/**
 * Created by younger on 2018/4/25.
 */
@Service
public class DevopsEnvResourceServiceImpl implements DevopsEnvResourceService {

    private static final String LINE_SEPARATOR = "line.separator";
    private static final String NONE_LABEL = "<none>";
    private static JSON json = new JSON();

    @Autowired
    private DevopsEnvResourceMapper devopsEnvResourceMapper;
    @Autowired
    private DevopsEnvCommandService devopsEnvCommandService;
    @Autowired
    private IamServiceClientOperator iamServiceClientOperator;
    @Autowired
    private DevopsCommandEventService devopsCommandEventService;
    @Autowired
    private DevopsEnvCommandLogService devopsEnvCommandLogService;
    @Autowired
    private DevopsEnvResourceDetailService  devopsEnvResourceDetailService;
    @Autowired
    private ApplicationInstanceService  applicationInstanceService;
    @Autowired
    private DevopsEnvResourceService devopsEnvResourceService;
    @Autowired
    private DevopsServiceService devopsServiceService;
    @Autowired
    private DevopsIngressService devopsIngressService;

    @Override
    public DevopsEnvResourceVO listResourcesInHelmRelease(Long instanceId) {
        ApplicationInstanceDTO applicationInstanceDTO = applicationInstanceService.baseQuery(instanceId);
        List<DevopsEnvResourceDTO> devopsEnvResourceDTOS =
                devopsEnvResourceService.baseListByInstanceId(instanceId);
        DevopsEnvResourceVO devopsEnvResourceDTO = new DevopsEnvResourceVO();
        if (devopsEnvResourceDTOS == null) {
            return devopsEnvResourceDTO;
        }

        // 关联资源
        devopsEnvResourceDTOS.forEach(envResourceDTO -> {
                    DevopsEnvResourceDetailDTO envResourceDetailDTO = devopsEnvResourceDetailService.baesQueryByMessageId(envResourceDTO.getResourceDetailId());
                    if (isReleaseGenerated(envResourceDetailDTO.getMessage())) {
                        dealWithResource(envResourceDetailDTO, envResourceDTO, devopsEnvResourceDTO, applicationInstanceDTO.getEnvId());
                    }
                }
        );
        return devopsEnvResourceDTO;
    }

    /**
     * 判断该资源是否是应用chart包中定义而生成资源
     *
     * @param message 资源的信息
     * @return true 如果是chart包定义生成的
     */
    private boolean isReleaseGenerated(String message) {
        try {
            JsonNode info = new ObjectMapper().readTree(message);
            return info.get("metadata").get("labels").get("choerodon.io/release") != null;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 处理获取的资源详情，将资源详情根据类型进行数据处理填入 devopsEnvResourceDTO 中
     *
     * @param devopsEnvResourceDetailDTO 资源详情
     * @param devopsEnvResourceDTO  资源
     * @param devopsEnvResourceVO     存放处理结果的dto
     * @param envId                    环境id
     */
    private void dealWithResource(DevopsEnvResourceDetailDTO devopsEnvResourceDetailDTO, DevopsEnvResourceDTO devopsEnvResourceDTO, DevopsEnvResourceVO devopsEnvResourceVO, Long envId) {
        ResourceType resourceType = ResourceType.forString(devopsEnvResourceDTO.getKind());
        if (resourceType == null) {
            resourceType = ResourceType.MISSTYPE;
        }
        switch (resourceType) {
            case POD:
                V1Pod v1Pod = json.deserialize(devopsEnvResourceDetailDTO.getMessage(), V1Pod.class);
                addPodToResource(devopsEnvResourceVO, v1Pod);
                break;
            case DEPLOYMENT:
                V1beta2Deployment v1beta2Deployment = json.deserialize(
                        devopsEnvResourceDetailDTO.getMessage(),
                        V1beta2Deployment.class);

                addDeploymentToResource(devopsEnvResourceVO, v1beta2Deployment);
                break;
            case SERVICE:
                V1Service v1Service = json.deserialize(devopsEnvResourceDetailDTO.getMessage(),
                        V1Service.class);
                DevopsServiceDTO devopsServiceDTO = devopsServiceService.baseQueryByNameAndEnvId(
                        devopsEnvResourceDTO.getName(), envId);
                if (devopsServiceDTO != null) {
                    List<String> domainNames =
                            devopsIngressService.baseListNameByServiceId(
                                    devopsServiceDTO.getId());
                    domainNames.forEach(domainName -> {
                        DevopsEnvResourceDTO newDevopsEnvResourceDTO =
                                baseQueryOptions(
                                        null,
                                        null,
                                        envId,
                                        "Ingress",
                                        domainName);
                        //升级0.11.0-0.12.0,资源表新增envId,修复以前的域名数据
                        if (newDevopsEnvResourceDTO == null) {
                            newDevopsEnvResourceDTO = baseQueryOptions(
                                    null,
                                    null,
                                    null,
                                    "Ingress",
                                    domainName);
                        }
                        if (newDevopsEnvResourceDTO != null) {
                            DevopsEnvResourceDetailDTO newDevopsEnvResourceDetailDTO =
                                    devopsEnvResourceDetailService.baesQueryByMessageId(
                                            newDevopsEnvResourceDTO.getResourceDetailId());
                            V1beta1Ingress v1beta1Ingress = json.deserialize(
                                    newDevopsEnvResourceDetailDTO.getMessage(),
                                    V1beta1Ingress.class);
                            devopsEnvResourceVO.getIngressVOS().add(addIngressToResource(v1beta1Ingress));
                        }
                    });
                }
                addServiceToResource(devopsEnvResourceVO, v1Service);
                break;
            case INGRESS:
                if (devopsEnvResourceDTO.getAppInstanceId() != null) {
                    V1beta1Ingress v1beta1Ingress = json.deserialize(
                            devopsEnvResourceDetailDTO.getMessage(),
                            V1beta1Ingress.class);
                    devopsEnvResourceVO.getIngressVOS().add(addIngressToResource(v1beta1Ingress));
                }
                break;
            case REPLICASET:
                V1beta2ReplicaSet v1beta2ReplicaSet = json.deserialize(
                        devopsEnvResourceDetailDTO.getMessage(),
                        V1beta2ReplicaSet.class);
                addReplicaSetToResource(devopsEnvResourceVO, v1beta2ReplicaSet);
                break;
            case DAEMONSET:
                V1beta2DaemonSet v1beta2DaemonSet = json.deserialize(devopsEnvResourceDetailDTO.getMessage(), V1beta2DaemonSet.class);
                addDaemonSetToResource(devopsEnvResourceVO, v1beta2DaemonSet);
                break;
            case STATEFULSET:
                V1beta2StatefulSet v1beta2StatefulSet = json.deserialize(devopsEnvResourceDetailDTO.getMessage(), V1beta2StatefulSet.class);
                addStatefulSetSetToResource(devopsEnvResourceVO, v1beta2StatefulSet);
                break;
            case PERSISTENT_VOLUME_CLAIM:
                V1PersistentVolumeClaim persistentVolumeClaim = json.deserialize(devopsEnvResourceDetailDTO.getMessage(), V1PersistentVolumeClaim.class);
                addPersistentVolumeClaimToResource(devopsEnvResourceVO, persistentVolumeClaim);
                break;
            default:
                break;
        }
    }


    @Override
    public List<InstanceEventVO> listInstancePodEvent(Long instanceId) {
        List<InstanceEventVO> instanceEventVOS = new ArrayList<>();
        List<DevopsEnvCommandDTO> devopsEnvCommandDTOS = devopsEnvCommandService
                .baseListInstanceCommand(ObjectType.INSTANCE.getType(), instanceId);
        devopsEnvCommandDTOS.forEach(devopsEnvCommandDTO -> {
            InstanceEventVO instanceEventVO = new InstanceEventVO();
            IamUserDTO iamUserDTO = iamServiceClientOperator.queryUserByUserId(devopsEnvCommandDTO.getCreatedBy());
            instanceEventVO.setLoginName(iamUserDTO == null ? null : iamUserDTO.getLoginName());
            instanceEventVO.setRealName(iamUserDTO == null ? null : iamUserDTO.getRealName());
            instanceEventVO.setStatus(devopsEnvCommandDTO.getStatus());
            instanceEventVO.setUserImage(iamUserDTO == null ? null : iamUserDTO.getImageUrl());
            instanceEventVO.setCreateTime(devopsEnvCommandDTO.getCreationDate());
            instanceEventVO.setType(devopsEnvCommandDTO.getCommandType());
            List<PodEventVO> podEventVOS = new ArrayList<>();
            //获取实例中job的event
            List<DevopsCommandEventDTO> devopsCommandEventDTOS = devopsCommandEventService
                    .baseListByCommandIdAndType(devopsEnvCommandDTO.getId(), ResourceType.JOB.getType());
            if (!devopsCommandEventDTOS.isEmpty()) {
                LinkedHashMap<String, String> jobEvents = getDevopsCommandEvent(devopsCommandEventDTOS);
                jobEvents.forEach((key, value) -> {
                    PodEventVO podEventVO = new PodEventVO();
                    podEventVO.setName(key);
                    podEventVO.setEvent(value);
                    podEventVOS.add(podEventVO);
                });
            }
            List<DevopsEnvResourceDTO> jobs = baseListByCommandId(devopsEnvCommandDTO.getId());
            List<DevopsEnvCommandLogDTO> devopsEnvCommandLogES = devopsEnvCommandLogService
                    .baseListByDeployId(devopsEnvCommandDTO.getId());
            for (int i = 0; i < jobs.size(); i++) {
                DevopsEnvResourceDTO job = jobs.get(i);
                DevopsEnvResourceDetailDTO devopsEnvResourceDetailDTO =
                        devopsEnvResourceDetailService.baesQueryByMessageId(
                                job.getResourceDetailId());
                V1Job v1Job = json.deserialize(devopsEnvResourceDetailDTO.getMessage(), V1Job.class);
                if (podEventVOS.size() < 4) {
                    //job日志
                    if (i <= devopsEnvCommandLogES.size() - 1) {
                        if (podEventVOS.size() == i) {
                            PodEventVO podEventVO = new PodEventVO();
                            podEventVO.setName(v1Job.getMetadata().getName());
                            podEventVOS.add(podEventVO);
                        }
                        podEventVOS.get(i).setLog(devopsEnvCommandLogES.get(i).getLog());
                    }
                    //获取job状态
                    if (i <= podEventVOS.size() - 1) {
                        if (podEventVOS.size() == i) {
                            PodEventVO podEventVO = new PodEventVO();
                            podEventVOS.add(podEventVO);
                        }
                        setJobStatus(v1Job, podEventVOS.get(i));
                    }
                }
            }
            //获取实例中pod的event
            List<DevopsCommandEventDTO> devopsCommandPodEventES = devopsCommandEventService
                    .baseListByCommandIdAndType(devopsEnvCommandDTO.getId(), ResourceType.POD.getType());
            if (!devopsCommandPodEventES.isEmpty()) {
                LinkedHashMap<String, String> podEvents = getDevopsCommandEvent(devopsCommandPodEventES);
                int index = 0;
                for (Map.Entry<String, String> entry : podEvents.entrySet()) {
                    PodEventVO podEventVO = new PodEventVO();
                    podEventVO.setName(entry.getKey());
                    podEventVO.setEvent(entry.getValue());
                    podEventVOS.add(podEventVO);
                    if (index++ >= 4) {
                        break;
                    }
                }
            }
            instanceEventVO.setPodEventVO(podEventVOS);
            if (!instanceEventVO.getPodEventVO().isEmpty()) {
                instanceEventVOS.add(instanceEventVO);
            }
        });
        return instanceEventVOS;
    }


    private void setJobStatus(V1Job v1Job, PodEventVO podEventVO) {
        if (v1Job.getStatus() != null) {
            if (v1Job.getStatus().getSucceeded() != null && v1Job.getStatus().getSucceeded() == 1) {
                podEventVO.setJobPodStatus("success");
            } else if (v1Job.getStatus().getFailed() != null) {
                podEventVO.setJobPodStatus("fail");
            } else {
                podEventVO.setJobPodStatus("running");
            }
        }
    }


    private LinkedHashMap<String, String> getDevopsCommandEvent(List<DevopsCommandEventDTO> devopsCommandEventDTOS) {
        devopsCommandEventDTOS.sort(Comparator.comparing(DevopsCommandEventDTO::getId));
        LinkedHashMap<String, String> event = new LinkedHashMap<>();
        for (DevopsCommandEventDTO devopsCommandEventDTO : devopsCommandEventDTOS) {
            if (!event.containsKey(devopsCommandEventDTO.getName())) {
                event.put(devopsCommandEventDTO.getName(), devopsCommandEventDTO.getMessage() + System.getProperty(LINE_SEPARATOR));
            } else {
                event.put(devopsCommandEventDTO.getName(), event.get(devopsCommandEventDTO.getName()) + devopsCommandEventDTO.getMessage() + System.getProperty(LINE_SEPARATOR));
            }
        }
        return event;
    }


    /**
     * 增加pod资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1Pod                pod对象
     */
    private void addPodToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1Pod v1Pod) {
        PodVO podVO = new PodVO();
        podVO.setName(v1Pod.getMetadata().getName());
        podVO.setDesire(TypeUtil.objToLong(v1Pod.getSpec().getContainers().size()));
        long ready = 0L;
        Long restart = 0L;
        if (v1Pod.getStatus().getContainerStatuses() != null) {
            for (V1ContainerStatus v1ContainerStatus : v1Pod.getStatus().getContainerStatuses()) {
                if (v1ContainerStatus.isReady() && v1ContainerStatus.getState().getRunning().getStartedAt() != null) {
                    ready = ready + 1;
                }
                restart = restart + v1ContainerStatus.getRestartCount();
            }
        }
        podVO.setReady(ready);
        podVO.setStatus(K8sUtil.changePodStatus(v1Pod));
        podVO.setRestarts(restart);
        podVO.setAge(v1Pod.getMetadata().getCreationTimestamp().toString());
        devopsEnvResourceDTO.getPodVOS().add(podVO);
    }

    /**
     * 增加deployment资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1beta2Deployment    deployment对象
     */
    public void addDeploymentToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1beta2Deployment v1beta2Deployment) {
        DeploymentVO deploymentVO = new DeploymentVO();
        deploymentVO.setName(v1beta2Deployment.getMetadata().getName());
        deploymentVO.setDesired(TypeUtil.objToLong(v1beta2Deployment.getSpec().getReplicas()));
        deploymentVO.setCurrent(TypeUtil.objToLong(v1beta2Deployment.getStatus().getReplicas()));
        deploymentVO.setUpToDate(TypeUtil.objToLong(v1beta2Deployment.getStatus().getUpdatedReplicas()));
        deploymentVO.setAvailable(TypeUtil.objToLong(v1beta2Deployment.getStatus().getAvailableReplicas()));
        deploymentVO.setAge(v1beta2Deployment.getMetadata().getCreationTimestamp().toString());
        deploymentVO.setLabels(v1beta2Deployment.getSpec().getSelector().getMatchLabels());
        List<Integer> portRes = new ArrayList<>();
        for (V1Container container : v1beta2Deployment.getSpec().getTemplate().getSpec().getContainers()) {
            List<V1ContainerPort> ports = container.getPorts();
            Optional.ofNullable(ports).ifPresent(portList -> {
                for (V1ContainerPort port : portList) {
                    portRes.add(port.getContainerPort());
                }
            });
        }
        deploymentVO.setPorts(portRes);
        if (v1beta2Deployment.getStatus() != null && v1beta2Deployment.getStatus().getConditions() != null) {
            v1beta2Deployment.getStatus().getConditions().forEach(v1beta2DeploymentCondition -> {
                if ("NewReplicaSetAvailable".equals(v1beta2DeploymentCondition.getReason())) {
                    deploymentVO.setAge(v1beta2DeploymentCondition.getLastUpdateTime().toString());
                }
            });
        }
        devopsEnvResourceDTO.getDeploymentVOS().add(deploymentVO);
    }

    /**
     * 增加service资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1Service            service对象
     */
    public void addServiceToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1Service v1Service) {
        ServiceVO serviceVO = new ServiceVO();
        serviceVO.setName(v1Service.getMetadata().getName());
        serviceVO.setType(v1Service.getSpec().getType());
        if (v1Service.getSpec().getClusterIP().length() == 0) {
            serviceVO.setClusterIp(NONE_LABEL);
        } else {
            serviceVO.setClusterIp(v1Service.getSpec().getClusterIP());
        }
        serviceVO.setExternalIp(K8sUtil.getServiceExternalIp(v1Service));
        String port = K8sUtil.makePortString(v1Service.getSpec().getPorts());
        if (port.length() == 0) {
            port = NONE_LABEL;
        }
        String targetPort = K8sUtil.makeTargetPortString(v1Service.getSpec().getPorts());
        if (targetPort.length() == 0) {
            targetPort = NONE_LABEL;
        }
        serviceVO.setPort(port);
        serviceVO.setTargetPort(targetPort);
        serviceVO.setAge(v1Service.getMetadata().getCreationTimestamp().toString());
        devopsEnvResourceDTO.getServiceVOS().add(serviceVO);
    }

    /**
     * 增加ingress资源
     *
     * @param v1beta1Ingress ingress对象
     */
    public IngressVO addIngressToResource(V1beta1Ingress v1beta1Ingress) {
        IngressVO ingressVO = new IngressVO();
        ingressVO.setName(v1beta1Ingress.getMetadata().getName());
        ingressVO.setHosts(K8sUtil.formatHosts(v1beta1Ingress.getSpec().getRules()));
        ingressVO.setPorts(K8sUtil.formatPorts(v1beta1Ingress.getSpec().getTls()));
        ingressVO.setAddress(K8sUtil.loadBalancerStatusStringer(v1beta1Ingress.getStatus().getLoadBalancer()));
        ingressVO.setAge(v1beta1Ingress.getMetadata().getCreationTimestamp().toString());
        return ingressVO;
    }

    /**
     * 增加replicaSet资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1beta2ReplicaSet    replicaSet对象
     */
    public void addReplicaSetToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1beta2ReplicaSet v1beta2ReplicaSet) {
        if (v1beta2ReplicaSet.getSpec().getReplicas() == 0) {
            return;
        }
        ReplicaSetVO replicaSetVO = new ReplicaSetVO();
        replicaSetVO.setName(v1beta2ReplicaSet.getMetadata().getName());
        replicaSetVO.setCurrent(TypeUtil.objToLong(v1beta2ReplicaSet.getStatus().getReplicas()));
        replicaSetVO.setDesired(TypeUtil.objToLong(v1beta2ReplicaSet.getSpec().getReplicas()));
        replicaSetVO.setReady(TypeUtil.objToLong(v1beta2ReplicaSet.getStatus().getReadyReplicas()));
        replicaSetVO.setAge(v1beta2ReplicaSet.getMetadata().getCreationTimestamp().toString());
        devopsEnvResourceDTO.getReplicaSetVOS().add(replicaSetVO);
    }

    /**
     * 添加daemonSet类型资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1beta2DaemonSet     daemonSet对象
     */
    private void addDaemonSetToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1beta2DaemonSet v1beta2DaemonSet) {
        DaemonSetVO daemonSetVO = new DaemonSetVO();
        daemonSetVO.setName(v1beta2DaemonSet.getMetadata().getName());
        daemonSetVO.setAge(v1beta2DaemonSet.getMetadata().getCreationTimestamp().toString());
        daemonSetVO.setCurrentScheduled(TypeUtil.objToLong(v1beta2DaemonSet.getStatus().getCurrentNumberScheduled()));
        daemonSetVO.setDesiredScheduled(TypeUtil.objToLong(v1beta2DaemonSet.getStatus().getDesiredNumberScheduled()));
        daemonSetVO.setNumberAvailable(TypeUtil.objToLong(v1beta2DaemonSet.getStatus().getNumberAvailable()));

        devopsEnvResourceDTO.getDaemonSetVOS().add(daemonSetVO);
    }

    /**
     * 添加statefulSet类型资源
     *
     * @param devopsEnvResourceDTO 实例资源参数
     * @param v1beta2StatefulSet   statefulSet对象
     */
    private void addStatefulSetSetToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1beta2StatefulSet v1beta2StatefulSet) {
        StatefulSetVO statefulSetVO = new StatefulSetVO();
        statefulSetVO.setName(v1beta2StatefulSet.getMetadata().getName());
        statefulSetVO.setDesiredReplicas(TypeUtil.objToLong(v1beta2StatefulSet.getSpec().getReplicas()));
        statefulSetVO.setAge(v1beta2StatefulSet.getMetadata().getCreationTimestamp().toString());
        statefulSetVO.setReadyReplicas(TypeUtil.objToLong(v1beta2StatefulSet.getStatus().getReadyReplicas()));
        statefulSetVO.setCurrentReplicas(TypeUtil.objToLong(v1beta2StatefulSet.getStatus().getCurrentReplicas()));

        devopsEnvResourceDTO.getStatefulSetVOS().add(statefulSetVO);
    }

    /**
     * 添加persistentVolumeClaim类型资源
     *
     * @param devopsEnvResourceDTO    实例资源参数
     * @param v1PersistentVolumeClaim persistentVolumeClaim对象
     */
    private void addPersistentVolumeClaimToResource(DevopsEnvResourceVO devopsEnvResourceDTO, V1PersistentVolumeClaim v1PersistentVolumeClaim) {
        PersistentVolumeClaimVO dto = new PersistentVolumeClaimVO();
        dto.setName(v1PersistentVolumeClaim.getMetadata().getName());
        dto.setStatus(v1PersistentVolumeClaim.getStatus().getPhase());
        // 当PVC是Pending状态时，status字段下只有phase字段
        if ("Pending".equals(dto.getStatus())) {
            dto.setCapacity("0Gi");
        } else {
            dto.setCapacity(v1PersistentVolumeClaim.getStatus().getCapacity().get("storage").toSuffixedString());
        }
        dto.setAccessModes(v1PersistentVolumeClaim.getSpec().getAccessModes().toString());
        dto.setAge(v1PersistentVolumeClaim.getMetadata().getCreationTimestamp().toString());

        devopsEnvResourceDTO.getPersistentVolumeClaimVOS().add(dto);
    }

    /**
     * 获取时间间隔
     *
     * @param ttime1 起始时间
     * @param ttime2 结束时间
     * @return long[]
     */
    public Long[] getStageTime(Timestamp ttime1, Timestamp ttime2) {
        long day = 0;
        long hour = 0;
        long min = 0;
        long sec = 0;
        long time1 = ttime1.getTime();
        long time2 = ttime2.getTime();
        long diff;
        if (time1 < time2) {
            diff = time2 - time1;
        } else {
            diff = time1 - time2;
        }
        day = diff / (24 * 60 * 60 * 1000);
        hour = (diff / (60 * 60 * 1000) - day * 24);
        min = ((diff / (60 * 1000)) - day * 24 * 60 - hour * 60);
        sec = (diff / 1000 - day * 24 * 60 * 60 - hour * 60 * 60 - min * 60);
        return new Long[]{day, hour, min, sec};
    }


    @Override
    public void baseCreate(DevopsEnvResourceDTO devopsEnvResourceDTO) {
        if (devopsEnvResourceMapper.insert(devopsEnvResourceDTO) != 1) {
            throw new CommonException("error.resource.insert");
        }
    }

    @Override
    public List<DevopsEnvResourceDTO> baseListByInstanceId(Long instanceId) {
        DevopsEnvResourceDTO devopsEnvResourceDTO = new DevopsEnvResourceDTO();
        devopsEnvResourceDTO.setAppInstanceId(instanceId);
        return devopsEnvResourceMapper.select(devopsEnvResourceDTO);
    }

    @Override
    public List<DevopsEnvResourceDTO> baseListByCommandId(Long commandId) {
        return devopsEnvResourceMapper.listJobs(commandId);
    }

    @Override
    public void baseUpdate(DevopsEnvResourceDTO devopsEnvResourceDTO) {
        devopsEnvResourceDTO.setObjectVersionNumber(
                devopsEnvResourceMapper.selectByPrimaryKey(
                        devopsEnvResourceDTO.getId()).getObjectVersionNumber());
        if (devopsEnvResourceMapper.updateByPrimaryKeySelective(devopsEnvResourceDTO) != 1) {
            throw new CommonException("error.resource.update");
        }
    }

    @Override
    public void deleteByEnvIdAndKindAndName(Long envId, String kind, String name) {
        DevopsEnvResourceDTO devopsEnvResourceDO = new DevopsEnvResourceDTO();
        if (devopsEnvResourceMapper.queryResource(null, null, envId, kind, name) != null) {
            devopsEnvResourceDO.setEnvId(envId);
        }
        devopsEnvResourceDO.setKind(kind);
        devopsEnvResourceDO.setName(name);
        devopsEnvResourceMapper.delete(devopsEnvResourceDO);
    }

    @Override
    public List<DevopsEnvResourceDTO> baseListByEnvAndType(Long envId, String type) {
        return devopsEnvResourceMapper.listByEnvAndType(envId, type);
    }

    @Override
    public DevopsEnvResourceDTO baseQueryByKindAndName(String kind, String name) {
        return devopsEnvResourceMapper.queryLatestJob(kind, name);
    }

    @Override
    public void deleteByKindAndNameAndInstanceId(String kind, String name, Long instanceId) {
        DevopsEnvResourceDTO devopsEnvResourceDTO = new DevopsEnvResourceDTO();
        devopsEnvResourceDTO.setKind(kind);
        devopsEnvResourceDTO.setName(name);
        devopsEnvResourceDTO.setAppInstanceId(instanceId);
        devopsEnvResourceMapper.delete(devopsEnvResourceDTO);
    }

    @Override
    public DevopsEnvResourceDTO baseQueryOptions(Long instanceId, Long commandId, Long envId, String kind, String name) {
        return devopsEnvResourceMapper.queryResource(instanceId, commandId, envId, kind, name);
    }


    @Override
    public String getResourceDetailByNameAndTypeAndInstanceId(Long instanceId, String name, ResourceType resourceType) {
        return devopsEnvResourceMapper.getResourceDetailByNameAndTypeAndInstanceId(instanceId, name, resourceType.getType());
    }
}
