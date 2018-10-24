package io.choerodon.devops.api.controller.v1

import io.choerodon.core.domain.Page
import io.choerodon.devops.IntegrationTestConfiguration
import io.choerodon.devops.api.dto.AppMarketTgzDTO
import io.choerodon.devops.api.dto.AppMarketVersionDTO
import io.choerodon.devops.api.dto.ApplicationReleasingDTO
import io.choerodon.devops.domain.application.entity.ProjectE
import io.choerodon.devops.domain.application.entity.UserAttrE
import io.choerodon.devops.domain.application.repository.IamRepository
import io.choerodon.devops.domain.application.valueobject.Organization
import io.choerodon.devops.infra.dataobject.*
import io.choerodon.devops.infra.mapper.*
import io.choerodon.mybatis.pagehelper.domain.PageRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Subject

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * Created by n!Ck
 * Date: 2018/10/23
 * Time: 21:34
 * Description: 
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(IntegrationTestConfiguration)
@Subject(ApplicationMarketController)
@Stepwise
class ApplicationMarketControllerSpec extends Specification {

    @Autowired
    private TestRestTemplate restTemplate

    @Autowired
    private ApplicationMapper applicationMapper
    @Autowired
    private DevopsEnvironmentMapper devopsEnvironmentMapper
    @Autowired
    private ApplicationMarketMapper applicationMarketMapper
    @Autowired
    private ApplicationVersionMapper applicationVersionMapper
    @Autowired
    private ApplicationInstanceMapper applicationInstanceMapper
    @Autowired
    private ApplicationVersionReadmeMapper applicationVersionReadmeMapper

    @Autowired
    @Qualifier("mockIamRepository")
    private IamRepository iamRepository

    @Shared
    ApplicationDO applicationDO = new ApplicationDO()
    @Shared
    DevopsEnvironmentDO devopsEnvironmentDO = new DevopsEnvironmentDO()
    @Shared
    ApplicationVersionDO applicationVersionDO = new ApplicationVersionDO()
    @Shared
    ApplicationInstanceDO applicationInstanceDO = new ApplicationInstanceDO()

    @Shared
    Organization organization = new Organization()
    @Shared
    ProjectE projectE = new ProjectE()
    @Shared
    UserAttrE userAttrE = new UserAttrE()
    @Shared
    Map<String, Object> searchParam = new HashMap<>()
    @Shared
    PageRequest pageRequest = new PageRequest()
    @Shared
    Long project_id = 1L
    @Shared
    Long init_id = 1L

    def setupSpec() {
        organization.setId(init_id)
        organization.setCode("org")

        projectE.setId(init_id)
        projectE.setCode("pro")
        projectE.setOrganization(organization)

        userAttrE.setIamUserId(init_id)
        userAttrE.setGitlabUserId(init_id)

        Map<String, Object> xxx = new HashMap<>()
        xxx.put("name", [])
        xxx.put("code", ["app"])
        searchParam.put("searchParam", xxx)
        searchParam.put("param", "")

        pageRequest.size = 10
        pageRequest.page = 0

        // da
        applicationDO.setId(1L)
        applicationDO.setActive(true)
        applicationDO.setProjectId(1L)
        applicationDO.setCode("appCode")
        applicationDO.setName("appName")

        // dav
        applicationVersionDO.setId(1L)
        applicationVersionDO.setAppId(1L)
        applicationVersionDO.setIsPublish(1L)
        applicationVersionDO.setVersion("0.0")
        applicationVersionDO.setReadmeValueId(1L)

        // dai
        applicationInstanceDO.setId(1L)
        applicationInstanceDO.setEnvId(1L)
        applicationInstanceDO.setAppId(1L)

        // de
        devopsEnvironmentDO.setId(1L)
        devopsEnvironmentDO.setProjectId(2L)
    }

    def "Create"() {
        given: '插入数据'
        applicationMapper.insert(applicationDO)
        applicationVersionMapper.insert(applicationVersionDO)

        and: '准备DTO'
        ApplicationReleasingDTO applicationReleasingDTO = new ApplicationReleasingDTO()
        applicationReleasingDTO.setAppId(1L)
        applicationReleasingDTO.setImgUrl("imgUrl")
        applicationReleasingDTO.setCategory("category")
        applicationReleasingDTO.setContributor("contributor")
        applicationReleasingDTO.setDescription("description")
        applicationReleasingDTO.setPublishLevel("organization")

        and: '应用版本'
        List<AppMarketVersionDTO> appVersions = new ArrayList<>()
        AppMarketVersionDTO appMarketVersionDTO = new AppMarketVersionDTO()
        appMarketVersionDTO.setId(1L)
        appVersions.add(appMarketVersionDTO)
        applicationReleasingDTO.setAppVersions(appVersions)

        when: '应用发布'
        def marketId = restTemplate.postForObject("/v1/projects/1/apps_market", applicationReleasingDTO, Long.class)

        then: '验证创建后的id'
        marketId == 1L
    }

    def "PageListMarketAppsByProjectId"() {
        given: '插入数据'
        devopsEnvironmentMapper.insert(devopsEnvironmentDO)
        applicationInstanceMapper.insert(applicationInstanceDO)

        and: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '查询所有发布在应用市场的应用'
        def page = restTemplate.postForObject("/v1/projects/1/apps_market/list", searchParam, Page.class)

        then:
        page.getContent().get(0)["name"] == "appName"
    }

    def "ListAllApp"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '查询发布级别为全局或者在本组织下的所有应用市场的应用'
        def page = restTemplate.postForObject("/v1/projects/1/apps_market/list_all", searchParam, Page.class)

        then:
        page.getContent().get(0)["name"] == "appName"
    }

    def "QueryAppInProject"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '查询项目下单个应用市场的应用详情'
        def dto = restTemplate.getForObject("/v1/projects/1/apps_market/1/detail", ApplicationReleasingDTO.class)

        then:
        dto.getName() == "appName"
    }

    def "QueryApp"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '查询项目下单个应用市场的应用详情'
        def dto = restTemplate.getForObject("/v1/projects/1/apps_market/1", ApplicationReleasingDTO.class)

        then:
        dto.getName() == "appName"
    }

    def "QueryAppVersionsInProject"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '查询项目下单个应用市场的应用的版本'
        def list = restTemplate.getForObject("/v1/projects/1/apps_market/1/versions", List.class)

        then:
        list.get(0)["id"] == 1
    }

    def "QueryAppVersionsInProjectByPage"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '分页查询项目下单个应用市场的应用的版本'
        def page = restTemplate.postForObject("/v1/projects/1/apps_market/1/versions", searchParam, Page.class)

        then:
        page.getContent().get(0)["id"] == 1
    }

    def "QueryAppVersionReadme"() {
        given: '插入App Version Readme'
        ApplicationVersionReadmeDO applicationVersionReadmeDO = new ApplicationVersionReadmeDO()
        applicationVersionReadmeDO.setId(1L)
        applicationVersionReadmeDO.setReadme("readme")
        applicationVersionReadmeMapper.insert(applicationVersionReadmeDO)

        when: '查询单个应用市场的应用的单个版本README'
        def str = restTemplate.getForObject("/v1/projects/1/apps_market/1/versions/1/readme", String.class)

        then:
        str == "readme"
    }

    def "Update"() {
        given: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        and: '准备DTO'
        ApplicationReleasingDTO applicationReleasingDTO = new ApplicationReleasingDTO()
        applicationReleasingDTO.setId(1L)
        applicationReleasingDTO.setContributor("newContributor")
        applicationReleasingDTO.setPublishLevel("organization")

        when: '更新单个应用市场的应用'
        restTemplate.put("/v1/projects/1/apps_market/1", applicationReleasingDTO)

        then: '验证更新后的contributor字段'
        applicationMarketMapper.selectByPrimaryKey(1L).getContributor() == "newContributor"
    }

    def "UpdateVersions"() {
        given: '准备dotList'
        AppMarketVersionDTO appMarketVersionDTO = new AppMarketVersionDTO()
        appMarketVersionDTO.setId(1L)
        List<AppMarketVersionDTO> dtoList = new ArrayList<>()
        dtoList.add(appMarketVersionDTO)

        and: '设置默认值'
        List<ProjectE> projectEList = new ArrayList<>()
        projectEList.add(projectE)
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.listIamProjectByOrgId(_, _) >> projectEList

        when: '更新单个应用市场的应用'
        restTemplate.put("/v1/projects/1/apps_market/1/versions", dtoList)

        then:
        applicationMarketMapper.selectByPrimaryKey(1L).getId() == 1
    }

    def "UploadApps"() {
        given: '设置multipartFile'
        HttpHeaders headers = new HttpHeaders()
        headers.setContentType(MediaType.parseMediaType("multipart/form-data"))

        MultiValueMap<String, Object> map = new LinkedMultiValueMap<String, Object>()
        FileSystemResource fileSystemResource = new FileSystemResource("src/test/resources/chart.zip")
        map.add("file", fileSystemResource)
        map.add("filename", fileSystemResource.getFilename())

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<MultiValueMap<String, Object>>(map, headers)

        and: '设置默认值'
        iamRepository.queryIamProject(_ as Long) >> projectE
        iamRepository.queryOrganizationById(_ as Long) >> organization

        when: '更新单个应用市场的应用'
        def dto = restTemplate.postForObject("/v1/projects/1/apps_market/upload", requestEntity, AppMarketTgzDTO.class)

        then:
        dto.getAppMarketList().get(0).getId() == 27
    }

    def "ImportApps"() {
    }

    def "DeleteZip"() {
    }

    def "ExportFile"() {
    }
}
