package io.choerodon.devops.app.service.impl;

import java.io.IOException;
import java.util.*;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.gson.Gson;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

import io.choerodon.base.domain.PageRequest;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.devops.api.vo.ConfigVO;
import io.choerodon.devops.api.vo.DefaultConfigVO;
import io.choerodon.devops.api.vo.DevopsConfigRepVO;
import io.choerodon.devops.api.vo.DevopsConfigVO;
import io.choerodon.devops.app.service.*;
import io.choerodon.devops.infra.config.ConfigurationProperties;
import io.choerodon.devops.infra.config.HarborConfigurationProperties;
import io.choerodon.devops.infra.dto.AppServiceDTO;
import io.choerodon.devops.infra.dto.DevopsConfigDTO;
import io.choerodon.devops.infra.dto.DevopsProjectDTO;
import io.choerodon.devops.infra.dto.HarborUserDTO;
import io.choerodon.devops.infra.dto.harbor.*;
import io.choerodon.devops.infra.dto.iam.OrganizationDTO;
import io.choerodon.devops.infra.dto.iam.ProjectDTO;
import io.choerodon.devops.infra.feign.HarborClient;
import io.choerodon.devops.infra.feign.operator.BaseServiceClientOperator;
import io.choerodon.devops.infra.handler.RetrofitHandler;
import io.choerodon.devops.infra.mapper.DevopsConfigMapper;
import io.choerodon.devops.infra.util.GenerateUUID;
import io.choerodon.devops.infra.util.PageRequestUtil;
import io.choerodon.devops.infra.util.TypeUtil;

/**
 * @author zongw.lee@gmail.com
 * @since 2019/03/11
 */
@Service
public class DevopsConfigServiceImpl implements DevopsConfigService {

    public static final String APP_SERVICE = "appService";
    private static final String HARBOR = "harbor";
    private static final String AUTHTYPE_PULL = "pull";
    private static final String AUTHTYPE_PUSH = "push";
    private static final String CHART = "chart";
    private static final String CUSTOM = "custom";
    private static final Gson gson = new Gson();
    private static final String USER_PREFIX = "User%s%s";
    private static final String USER_PREFIX_PULL = "pullUser%s%s";

    @Autowired
    private DevopsConfigMapper devopsConfigMapper;

    @Autowired
    private BaseServiceClientOperator baseServiceClientOperator;

    @Autowired
    private HarborConfigurationProperties harborConfigurationProperties;

    @Autowired
    private DevopsProjectService devopsProjectService;

    @Autowired
    private AppServiceService appServiceService;

    @Autowired
    private AppServiceVersionService appServiceVersionService;

    @Autowired
    private HarborService harborService;

    @Autowired
    private DevopsHarborUserService devopsHarborUserService;

    @Override
    public void operate(Long resourceId, String resourceType, List<DevopsConfigVO> devopsConfigVOS) {

        devopsConfigVOS.forEach(devopsConfigVO -> {
            //根据每个配置的默认还是自定义执行不同逻辑
            if (devopsConfigVO.getCustom()) {

                //自定义的harbor类型,不管是新建还是更新，当传进来有harbor project时都要检验project是否是私有
                if (devopsConfigVO.getType().equals(HARBOR) && devopsConfigVO.getConfig().getProject() != null) {
                    checkRegistryProjectIsPrivate(devopsConfigVO);
                }
                if (devopsConfigVO.getType().equals(HARBOR)) {
                    appServiceService.checkHarbor(devopsConfigVO.getConfig().getUrl(), devopsConfigVO.getConfig().getUserName(), devopsConfigVO.getConfig().getPassword(), devopsConfigVO.getConfig().getProject(), devopsConfigVO.getConfig().getEmail());
                    if (devopsConfigVO.getConfig().getProject() == null && !resourceType.equals(ResourceLevel.ORGANIZATION.value())) {
                        harborConfigurationProperties.setUsername(devopsConfigVO.getConfig().getUserName());
                        harborConfigurationProperties.setPassword(devopsConfigVO.getConfig().getPassword());
                        harborConfigurationProperties.setBaseUrl(devopsConfigVO.getConfig().getUrl());
                        ConfigurationProperties configurationProperties = new ConfigurationProperties(harborConfigurationProperties);
                        configurationProperties.setType(HARBOR);
                        Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
                        HarborClient harborClient = retrofit.create(HarborClient.class);

                        ProjectDTO projectDTO = null;
                        OrganizationDTO organizationDTO = null;
                        if (resourceType.equals(ResourceLevel.PROJECT.value())) {
                            projectDTO = baseServiceClientOperator.queryIamProjectById(resourceId);
                            organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
                        } else {
                            AppServiceDTO appServiceDTO = appServiceService.baseQuery(resourceId);
                            projectDTO = baseServiceClientOperator.queryIamProjectById(appServiceDTO.getProjectId());
                            organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
                        }
                        harborService.createHarbor(harborClient, projectDTO.getId(), organizationDTO.getCode() + "-" + projectDTO.getCode(), false, devopsConfigVO.getHarborPrivate());
                    }
                }
                //根据配置所在的资源层级，查询出数据库中是否存在
                DevopsConfigDTO devopsConfigDTO = baseQueryByResourceAndType(resourceId, resourceType, devopsConfigVO.getType());
                DevopsConfigDTO newDevopsConfigDTO = voToDto(devopsConfigVO);
                if (devopsConfigDTO != null) {
                    // 存在判断是否已经生成服务版本，无服务版本，直接覆盖更新；有服务版本，将原config对应的resourceId设置为null,新建config
                    if (appServiceVersionService.isVersionUseConfig(devopsConfigDTO.getId(), devopsConfigVO.getType())) {
                        updateResourceId(devopsConfigDTO.getId());
                        setResourceId(resourceId, resourceType, newDevopsConfigDTO);
                        newDevopsConfigDTO.setId(null);
                        baseCreate(newDevopsConfigDTO);
                    } else {
                        newDevopsConfigDTO.setId(devopsConfigDTO.getId());
                        setResourceId(resourceId, resourceType, newDevopsConfigDTO);
                        baseUpdate(newDevopsConfigDTO);
                    }
                } else {
                    setResourceId(resourceId, resourceType, newDevopsConfigDTO);
                    newDevopsConfigDTO.setId(null);
                    baseCreate(newDevopsConfigDTO);
                }
            } else {
                //默认的harbor类型,在项目层级有设置私有的功能
                if (devopsConfigVO.getType().equals(HARBOR) && resourceType.equals(ResourceLevel.PROJECT.value())) {
                    DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(resourceId);
                    //判断当前默认仓库私有配置是否和数据库中存储一致，不一致则执行对应逻辑,注意，只能将系统默认的harhor配置设置为私有
                    if (!devopsProjectDTO.getHarborProjectIsPrivate().equals(devopsConfigVO.getHarborPrivate())) {
                        operateHarborProject(resourceId, devopsConfigVO.getHarborPrivate());
                    }
                }
                //根据配置所在的资源层级，查询出数据库中是否存在，存在则删除
                DevopsConfigDTO devopsConfigDTO = baseQueryByResourceAndType(resourceId, resourceType, devopsConfigVO.getType());
                if (devopsConfigDTO != null) {
                    if (appServiceVersionService.isVersionUseConfig(devopsConfigDTO.getId(), devopsConfigVO.getType())) {
                        updateResourceId(devopsConfigDTO.getId());
                    } else {
                        baseDelete(devopsConfigDTO.getId());
                    }
                }
            }
        });
    }


    @Override
    public List<DevopsConfigVO> queryByResourceId(Long resourceId, String resourceType) {

        List<DevopsConfigVO> devopsConfigVOS = new ArrayList<>();

        List<DevopsConfigDTO> devopsConfigDTOS = baseListByResource(resourceId, resourceType);
        devopsConfigDTOS.forEach(devopsConfigDTO -> {
            DevopsConfigVO devopsConfigVO = dtoToVo(devopsConfigDTO);
            //如果是项目层级下的harbor类型，需返回是否私有
            if (devopsConfigVO.getProjectId() != null && devopsConfigVO.getType().equals(HARBOR)) {
                DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(devopsConfigVO.getProjectId());
                devopsConfigVO.setHarborPrivate(devopsProjectDTO.getHarborProjectIsPrivate());
            }
            devopsConfigVOS.add(devopsConfigVO);
        });
        return devopsConfigVOS;
    }


    private void operateHarborProject(Long projectId, Boolean harborPrivate) {
        ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(projectId);
        OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
        ConfigurationProperties configurationProperties = new ConfigurationProperties(harborConfigurationProperties);
        configurationProperties.setType(HARBOR);
        Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
        HarborClient harborClient = retrofit.create(HarborClient.class);
        if (harborPrivate) {
            //设置为私有后将harbor项目设置为私有
            DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(projectId);
            HarborUserDTO harborUserDTO = devopsHarborUserService.queryHarborUserById(devopsProjectDTO.getHarborUserId());
            HarborUserDTO harborPullUserDTO = devopsHarborUserService.queryHarborUserById(devopsProjectDTO.getHarborPullUserId());
            User user = harborService.convertUser(projectDTO,true,harborUserDTO.getHarborProjectUserName());
            User pullUser = harborService.convertUser(projectDTO,false,harborPullUserDTO.getHarborProjectUserName());
            //创建用户
            createUser(harborClient, user, Arrays.asList(1), organizationDTO, projectDTO);
            createUser(harborClient, pullUser, Arrays.asList(3), organizationDTO, projectDTO);

            //更新项目表
            HarborUserDTO harborUser = new HarborUserDTO(user.getUsername(), user.getPassword(), user.getEmail(), true);
            HarborUserDTO pullHarborUser = new HarborUserDTO(pullUser.getUsername(), pullUser.getPassword(), pullUser.getEmail(), false);
            if (devopsHarborUserService.create(harborUser) != 1) {
                throw new CommonException("error.harbor.user.insert");
            } else {
                devopsProjectDTO.setHarborUserId(harborUser.getId());
            }
            if (devopsHarborUserService.create(pullHarborUser) != 1) {
                throw new CommonException("error.harbor.pull.user.insert");
            } else {
                devopsProjectDTO.setHarborPullUserId(pullHarborUser.getId());
            }

            devopsProjectDTO.setHarborProjectIsPrivate(true);
            devopsProjectService.baseUpdate(devopsProjectDTO);
        } else {
            //设置为公有后将harbor项目设置为公有,删除成员角色
            try {
                Response<List<ProjectDetail>> projects = harborClient.listProject(organizationDTO.getCode() + "-" + projectDTO.getCode()).execute();
                if (!CollectionUtils.isEmpty(projects.body())) {
                    ProjectDetail projectDetail = new ProjectDetail();
                    Metadata metadata = new Metadata();
                    metadata.setHarborPublic("true");
                    projectDetail.setMetadata(metadata);
                    Response<Void> result = harborClient.updateProject(projects.body().get(0).getProjectId(), projectDetail).execute();
                    if (result.raw().code() != 200) {
                        throw new CommonException(result.errorBody().toString());
                    }
                    Response<SystemInfo> systemInfoResponse = harborClient.getSystemInfo().execute();
                    if (systemInfoResponse.raw().code() != 200) {
                        throw new CommonException(systemInfoResponse.errorBody().string());
                    }
                    if (systemInfoResponse.body().getHarborVersion().equals("v1.4.0")) {
                        Response<List<User>> users = harborClient.listUser(String.format(USER_PREFIX, organizationDTO.getId(), projectId)).execute();
                        if (users.raw().code() != 200) {
                            throw new CommonException(users.errorBody().string());
                        }
                        if (!ObjectUtils.isEmpty(users.body())) {
                            users.body().stream().forEach(user -> {
                                try {
                                    harborClient.deleteLowVersionMember(projects.body().get(0).getProjectId(), user.getUserId().intValue()).execute();
                                } catch (IOException e) {
                                    throw new CommonException("error.delete.harbor.member");
                                }
                            });
                        }

                    } else {
                        Response<List<ProjectMember>> projectMembers = harborClient.getProjectMembers(projects.body().get(0).getProjectId(), String.format(USER_PREFIX, organizationDTO.getId(), projectId)).execute();
                        if (projectMembers.raw().code() != 200) {
                            throw new CommonException(projectMembers.errorBody().string());
                        }
                        if (!ObjectUtils.isEmpty(projectMembers.body())) {
                            projectMembers.body().stream().forEach(projectMember -> {
                                try {
                                    harborClient.deleteMember(projects.body().get(0).getProjectId(), projectMember.getId()).execute();
                                } catch (IOException e) {
                                    throw new CommonException("error.delete.harbor.member");
                                }
                            });
                        }

                    }

                    DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(projectId);
                    devopsProjectDTO.setHarborProjectIsPrivate(false);
                    devopsProjectDTO.setHarborPullUserId(null);
                    devopsProjectDTO.setHarborUserId(null);
                    devopsProjectService.baseUpdate(devopsProjectDTO);

                }
            } catch (Exception e) {
                throw new CommonException(e);
            }
        }

    }

    @Override
    public DefaultConfigVO queryDefaultConfig(Long resourceId, String resourceType) {
        DefaultConfigVO defaultConfigVO = new DefaultConfigVO();

        //查询当前资源层级数据库中是否有对应的组件设置，有则返回url,无返回空，代表使用默认
        DevopsConfigDTO harborConfig = baseQueryByResourceAndType(resourceId, resourceType, HARBOR);
        if (harborConfig != null) {
            defaultConfigVO.setHarborConfigUrl(gson.fromJson(harborConfig.getConfig(), ConfigVO.class).getUrl());
        }
        DevopsConfigDTO chartConfig = baseQueryByResourceAndType(resourceId, resourceType, CHART);
        if (chartConfig != null) {
            defaultConfigVO.setChartConfigUrl(gson.fromJson(chartConfig.getConfig(), ConfigVO.class).getUrl());
        }
        return defaultConfigVO;
    }

    @Override
    public DevopsConfigDTO queryRealConfig(Long resourceId, String resourceType, String configType, String operateType) {
        //应用服务层次，先找应用配置，在找项目配置,最后找组织配置,项目和组织层次同理
        DevopsConfigDTO defaultConfig = baseQueryDefaultConfig(configType);
        if (resourceType.equals(APP_SERVICE)) {
            DevopsConfigDTO appServiceConfig = baseQueryByResourceAndType(resourceId, resourceType, configType);
            if (appServiceConfig != null) {
                return appServiceConfig;
            }
            AppServiceDTO appServiceDTO = appServiceService.baseQuery(resourceId);
            if (appServiceDTO.getProjectId() == null) {
                return defaultConfig;
            }
            DevopsConfigDTO projectConfig = baseQueryByResourceAndType(appServiceDTO.getProjectId(), ResourceLevel.PROJECT.value(), configType);
            if (projectConfig != null) {
                return projectConfig;
            }
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(appServiceDTO.getProjectId());
            OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
            DevopsConfigDTO organizationConfig = baseQueryByResourceAndType(organizationDTO.getId(), ResourceLevel.ORGANIZATION.value(), configType);
            //如果组织层使用自定义设置，为了避免给组织层下所有项目都创一遍harborProject,则只在具体某个应用服务用到的时候，在去给应用服务所属的项目创建对应的harborProject
            if (organizationConfig != null) {
                if (configType.equals(HARBOR)) {
                    ConfigVO configVO = gson.fromJson(organizationConfig.getConfig(), ConfigVO.class);
                    harborConfigurationProperties.setUsername(configVO.getUserName());
                    harborConfigurationProperties.setPassword(configVO.getPassword());
                    harborConfigurationProperties.setBaseUrl(configVO.getUrl());
                    ConfigurationProperties configurationProperties = new ConfigurationProperties(harborConfigurationProperties);
                    configurationProperties.setType(HARBOR);
                    Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
                    HarborClient harborClient = retrofit.create(HarborClient.class);
                    projectDTO = baseServiceClientOperator.queryIamProjectById(appServiceDTO.getProjectId());
                    organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
                    harborService.createHarbor(harborClient, projectDTO.getId(), organizationDTO.getCode() + "-" + projectDTO.getCode(), false, true);
                }
                return organizationConfig;
            }
            //若应用服务最后查出来的配置是最高级的默认harbor配置，需要校验项目层是否将默认harbor设置成了私有，如设为私有，需要读取私有的授权信息，用于ci推镜像和部署secret
            if (configType.equals(HARBOR)) {
                DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(projectDTO.getId());
                ConfigVO configVO = gson.fromJson(defaultConfig.getConfig(), ConfigVO.class);
                HarborUserDTO harborUserDTO = new HarborUserDTO();
                if (operateType.equals(AUTHTYPE_PUSH)) {
                    harborUserDTO = devopsHarborUserService.queryHarborUserById(devopsProjectDTO.getHarborUserId());
                } else if (operateType.equals(AUTHTYPE_PULL)) {
                    harborUserDTO = devopsHarborUserService.queryHarborUserById(devopsProjectDTO.getHarborPullUserId());
                }
                configVO.setUserName(harborUserDTO.getHarborProjectUserName());
                configVO.setPassword(harborUserDTO.getHarborProjectUserPassword());
                configVO.setEmail(harborUserDTO.getHarborProjectUserEmail());
                if (devopsProjectDTO.getHarborProjectIsPrivate() != null && devopsProjectDTO.getHarborProjectIsPrivate()) {
                    configVO.setPrivate(true);
                }
                defaultConfig.setConfig(gson.toJson(configVO));
            }
            return defaultConfig;
        } else if (resourceType.equals(ResourceLevel.PROJECT.value())) {
            DevopsConfigDTO projectConfig = baseQueryByResourceAndType(resourceId, ResourceLevel.PROJECT.value(), configType);
            if (projectConfig != null) {
                return projectConfig;
            }
            ProjectDTO projectDTO = baseServiceClientOperator.queryIamProjectById(resourceId);
            OrganizationDTO organizationDTO = baseServiceClientOperator.queryOrganizationById(projectDTO.getOrganizationId());
            DevopsConfigDTO organizationConfig = baseQueryByResourceAndType(organizationDTO.getId(), ResourceLevel.ORGANIZATION.value(), configType);
            if (organizationConfig != null) {
                return organizationConfig;
            }
            return defaultConfig;
        } else {
            DevopsConfigDTO organizationConfig = baseQueryByResourceAndType(resourceId, ResourceLevel.ORGANIZATION.value(), configType);
            if (organizationConfig != null) {
                return organizationConfig;
            }
            return defaultConfig;
        }
    }

    @Override
    public DevopsConfigVO queryRealConfigVO(Long resourceId, String resourceType, String configType) {
        return dtoToVo(queryRealConfig(resourceId, resourceType, configType, AUTHTYPE_PULL));
    }

    @Override
    public DevopsConfigDTO baseCreate(DevopsConfigDTO devopsConfigDTO) {
        if (devopsConfigMapper.insert(devopsConfigDTO) != 1) {
            throw new CommonException("error.devops.project.config.create");
        }
        return devopsConfigDTO;
    }

    @Override
    public DevopsConfigDTO baseUpdate(DevopsConfigDTO devopsConfigDTO) {
        if (devopsConfigMapper.updateByPrimaryKeySelective(devopsConfigDTO) != 1) {
            throw new CommonException("error.devops.project.config.update");
        }
        return devopsConfigMapper.selectByPrimaryKey(devopsConfigDTO);
    }

    @Override
    public void updateResourceId(Long configId) {
        devopsConfigMapper.updateResourceId(configId);
    }

    @Override
    public DevopsConfigDTO baseQuery(Long id) {
        return devopsConfigMapper.selectByPrimaryKey(id);
    }

    @Override
    public DevopsConfigDTO baseQueryByName(Long projectId, String name) {
        DevopsConfigDTO paramDO = new DevopsConfigDTO();
        paramDO.setProjectId(projectId);
        paramDO.setName(name);
        return devopsConfigMapper.selectOne(paramDO);
    }

    @Override
    public DevopsConfigDTO baseCheckByName(String name) {
        return devopsConfigMapper.queryByNameWithNoProject(name);
    }

    @Override
    public PageInfo<DevopsConfigDTO> basePageByOptions(Long projectId, PageRequest pageRequest, String params) {
        Map<String, Object> mapParams = TypeUtil.castMapParams(params);

        return PageHelper.startPage(pageRequest.getPage(), pageRequest.getSize(), PageRequestUtil.getOrderBy(pageRequest))
                .doSelectPageInfo(() -> devopsConfigMapper.listByOptions(projectId,
                        TypeUtil.cast(mapParams.get(TypeUtil.SEARCH_PARAM)),
                        TypeUtil.cast(mapParams.get(TypeUtil.PARAMS)), PageRequestUtil.checkSortIsEmpty(pageRequest)));
    }

    @Override
    public void baseDelete(Long id) {
        if (devopsConfigMapper.deleteByPrimaryKey(id) != 1) {
            throw new CommonException("error.devops.project.config.delete");
        }
    }

    @Override
    public DevopsConfigDTO baseQueryByResourceAndType(Long resourceId, String resourceType, String type) {
        DevopsConfigDTO devopsConfigDTO = new DevopsConfigDTO();
        setResourceId(resourceId, resourceType, devopsConfigDTO);
        devopsConfigDTO.setType(type);
        return devopsConfigMapper.selectOne(devopsConfigDTO);
    }


    public DevopsConfigDTO baseQueryDefaultConfig(String type) {
        return devopsConfigMapper.queryDefaultConfig(type);
    }

    private void setResourceId(Long resourceId, String resourceType, DevopsConfigDTO devopsConfigDTO) {
        if (ResourceLevel.ORGANIZATION.value().equals(resourceType)) {
            devopsConfigDTO.setOrganizationId(resourceId);
        } else if (ResourceLevel.PROJECT.value().equals(resourceType)) {
            devopsConfigDTO.setProjectId(resourceId);
        } else {
            devopsConfigDTO.setAppServiceId(resourceId);
        }
    }

    public List<DevopsConfigDTO> baseListByResource(Long resourceId, String resourceType) {
        DevopsConfigDTO devopsConfigDTO = new DevopsConfigDTO();
        setResourceId(resourceId, resourceType, devopsConfigDTO);
        return devopsConfigMapper.select(devopsConfigDTO);
    }

    @Override
    public DevopsConfigVO dtoToVo(DevopsConfigDTO devopsConfigDTO) {
        DevopsConfigVO devopsConfigVO = new DevopsConfigVO();
        BeanUtils.copyProperties(devopsConfigDTO, devopsConfigVO);
        ConfigVO configVO = gson.fromJson(devopsConfigDTO.getConfig(), ConfigVO.class);
        devopsConfigVO.setConfig(configVO);
        return devopsConfigVO;
    }

    @Override
    public DevopsConfigDTO voToDto(DevopsConfigVO devopsConfigVO) {
        DevopsConfigDTO devopsConfigDTO = new DevopsConfigDTO();
        BeanUtils.copyProperties(devopsConfigVO, devopsConfigDTO);
        String configJson = gson.toJson(devopsConfigVO.getConfig());
        devopsConfigDTO.setConfig(configJson);
        return devopsConfigDTO;
    }

    private void configVOInToRepVO(DevopsConfigRepVO devopsConfigRepVO, DevopsConfigVO devopsConfigVO) {
        if (devopsConfigVO.getType().equals(HARBOR)) {
            devopsConfigRepVO.setHarbor(devopsConfigVO);
            devopsConfigRepVO.setHarborPrivate(devopsConfigVO.getHarborPrivate());
        } else if (devopsConfigVO.getType().equals(CHART)) {
            devopsConfigRepVO.setChart(devopsConfigVO);
        }
    }

    @Override
    public DevopsConfigRepVO queryConfig(Long resourceId, String resourceType) {
        DevopsConfigRepVO devopsConfigRepVO = new DevopsConfigRepVO();
        List<DevopsConfigVO> configVOS = queryByResourceId(resourceId, resourceType);
        configVOS.forEach(devopsConfigVO -> configVOInToRepVO(devopsConfigRepVO, devopsConfigVO));
        if (resourceType.equals(ResourceLevel.PROJECT.value())) {
            DevopsProjectDTO devopsProjectDTO = devopsProjectService.baseQueryByProjectId(resourceId);
            devopsConfigRepVO.setHarborPrivate(devopsProjectDTO.getHarborProjectIsPrivate());
        }
        return devopsConfigRepVO;
    }

    @Override
    public void operateConfig(Long organizationId, String resourceType, DevopsConfigRepVO devopsConfigRepVO) {
        List<DevopsConfigVO> configVOS = new ArrayList<>();
        DevopsConfigVO harbor = new DevopsConfigVO();
        DevopsConfigVO chart = new DevopsConfigVO();
        if (ObjectUtils.isEmpty(devopsConfigRepVO.getHarbor())) {
            harbor.setCustom(false);
            harbor.setType(HARBOR);
            harbor.setHarborPrivate(devopsConfigRepVO.getHarborPrivate());
            configVOS.add(harbor);
        } else {
            harbor = devopsConfigRepVO.getHarbor();
            harbor.setHarborPrivate(devopsConfigRepVO.getHarborPrivate());
            configVOS.add(harbor);
        }

        if (ObjectUtils.isEmpty(devopsConfigRepVO.getChart())) {
            chart.setCustom(false);
            chart.setType(CHART);
            configVOS.add(chart);
        } else {
            configVOS.add(devopsConfigRepVO.getChart());
        }
        operate(organizationId, resourceType, configVOS);
    }

    @Override
    public void deleteByConfigIds(Set<Long> configIds) {
        List<DevopsConfigDTO> devopsConfigDTOS = devopsConfigMapper.listByConfigs(configIds);
        devopsConfigDTOS.stream().filter(devopsConfigDTO -> devopsConfigDTO.getAppServiceId() != null)
                .forEach(devopsConfigDTO -> {
                    devopsConfigMapper.deleteByPrimaryKey(devopsConfigDTO.getId());
                });
    }

    private void checkRegistryProjectIsPrivate(DevopsConfigVO devopsConfigVO) {
        ConfigurationProperties configurationProperties = new ConfigurationProperties();
        configurationProperties.setBaseUrl(devopsConfigVO.getConfig().getUrl());
        configurationProperties.setUsername(devopsConfigVO.getConfig().getUserName());
        configurationProperties.setPassword(devopsConfigVO.getConfig().getPassword());
        configurationProperties.setInsecureSkipTlsVerify(false);
        configurationProperties.setProject(devopsConfigVO.getConfig().getProject());
        configurationProperties.setType(HARBOR);
        Retrofit retrofit = RetrofitHandler.initRetrofit(configurationProperties);
        HarborClient harborClient = retrofit.create(HarborClient.class);
        Call<List<ProjectDetail>> listProject = harborClient.listProject(devopsConfigVO.getConfig().getProject());
        Response<List<ProjectDetail>> projectResponse;
        try {
            projectResponse = listProject.execute();
            if (projectResponse != null && projectResponse.body() != null) {
                if ("false".equals(projectResponse.body().get(0).getMetadata().getHarborPublic())) {
                    devopsConfigVO.getConfig().setPrivate(true);
                } else {
                    devopsConfigVO.getConfig().setPrivate(false);
                }
            }
        } catch (IOException e) {
            throw new CommonException(e);
        }
    }

    private void createUser(HarborClient harborClient, User user, List<Integer> roles, OrganizationDTO organizationDTO, ProjectDTO projectDTO) {
        Response<Void> result = null;
        try {
            Response<List<User>> users = harborClient.listUser(user.getUsername()).execute();
            if (users.raw().code() != 200) {
                throw new CommonException(users.errorBody().string());
            }
            if (users.body().isEmpty()) {
                result = harborClient.insertUser(user).execute();
                if (result.raw().code() != 201 && result.raw().code() != 409) {
                    throw new CommonException(result.errorBody().string());
                }
            } else {
                Boolean exist = users.body().stream().anyMatch(user1 -> user1.getUsername().equals(user.getUsername()));
                if (!exist) {
                    result = harborClient.insertUser(user).execute();
                    if (result.raw().code() != 201 && result.raw().code() != 409) {
                        throw new CommonException(result.errorBody().string());
                    }
                }
            }
            //给项目绑定角色
            Response<List<ProjectDetail>> projects = harborClient.listProject(organizationDTO.getCode() + "-" + projectDTO.getCode()).execute();
            if (!projects.body().isEmpty()) {
                ProjectDetail projectDetail = new ProjectDetail();
                Metadata metadata = new Metadata();
                metadata.setHarborPublic("false");
                projectDetail.setMetadata(metadata);
                result = harborClient.updateProject(projects.body().get(0).getProjectId(), projectDetail).execute();
                if (result.raw().code() != 200) {
                    throw new CommonException(result.errorBody().string());
                }
                Response<SystemInfo> systemInfoResponse = harborClient.getSystemInfo().execute();
                if (systemInfoResponse.raw().code() != 200) {
                    throw new CommonException(systemInfoResponse.errorBody().string());
                }

                if (systemInfoResponse.body().getHarborVersion().equals("v1.4.0")) {
                    Role role = new Role();
                    role.setUsername(user.getUsername());
                    role.setRoles(roles);
                    result = harborClient.setProjectMember(projects.body().get(0).getProjectId(), role).execute();
                } else {
                    ProjectMember projectMember = new ProjectMember();
                    MemberUser memberUser = new MemberUser();
                    projectMember.setRoleId(roles.get(0));
                    memberUser.setUsername(user.getUsername());
                    projectMember.setMemberUser(memberUser);
                    result = harborClient.setProjectMember(projects.body().get(0).getProjectId(), projectMember).execute();
                }
                if (result.raw().code() != 201 && result.raw().code() != 200 && result.raw().code() != 409) {
                    throw new CommonException(result.errorBody().string());
                }
            }
        } catch (IOException e) {
            throw new CommonException(e);
        }

    }
}


