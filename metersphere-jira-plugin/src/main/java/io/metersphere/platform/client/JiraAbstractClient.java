package io.metersphere.platform.client;

import io.metersphere.platform.api.BaseClient;
import io.metersphere.platform.domain.*;
import io.metersphere.plugin.exception.MSPluginException;
import io.metersphere.plugin.utils.JSON;
import io.metersphere.plugin.utils.LogUtil;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public abstract class JiraAbstractClient extends BaseClient {

    protected  String ENDPOINT;

    protected  String PREFIX;

    protected  String USER_NAME;

    protected  String PASSWD;

    protected  String TOKEN;

    protected  String AUTH_TYPE;

    private static final String GREENHOPPER_V1_BASE_URL = "/rest/greenhopper/1.0";

    private static final String ISSUE_RELATE_FILTER_JQL = "project in projectsWhereUserHasPermission(\"Link Issues\") AND (resolution = Unresolved or statusCategory != Done) ORDER BY priority DESC, updated DESC";

    public JiraIssue getIssues(String issuesId) {
        LogUtil.info("getIssues: " + issuesId);
        ResponseEntity<String> responseEntity;
        responseEntity = restTemplate.exchange(getBaseUrl() + "/issue/" + issuesId, HttpMethod.GET, getAuthHttpEntity(), String.class);
        return  (JiraIssue) getResultForObject(JiraIssue.class, responseEntity);
    }

    public Map<String, JiraCreateMetadataResponse.Field> getCreateMetadata(String projectKey, String issueType) {
        String url = getBaseUrl() + "/issue/createmeta?projectKeys={1}&issuetypeIds={2}&expand=projects.issuetypes.fields";
        ResponseEntity<String> response = null;
        Map<String, JiraCreateMetadataResponse.Field> fields = null;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class, projectKey, issueType);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
        try {
            fields = ((JiraCreateMetadataResponse) getResultForObject(JiraCreateMetadataResponse.class, response))
                    .getProjects().get(0).getIssuetypes().get(0).getFields();
        } catch (Exception e) {
            LogUtil.error(e);
            MSPluginException.throwException("请检查服务集成信息或Jira项目ID");
        }
        fields.remove("project");
        fields.remove("issuetype");
        return fields;
    }

    public List<JiraIssueType> getIssueType(String projectKey) {
        JiraIssueProject project = getProject(projectKey);
        String url = getUrl("/issuetype/project?projectId={0}");
        ResponseEntity<String> response = null;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class, project.getId());
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() == 404) { // SaaS 的jira才有这个接口，报错则调用其他接口
                return this.getProject(projectKey).getIssueTypes();
            }
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
        return (List<JiraIssueType>) getResultForList(JiraIssueType.class, response);
    }

    public JiraIssueProject getProject(String projectKey) {
        String url = getUrl("/project/" + projectKey);
        ResponseEntity<String> response = null;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
        return (JiraIssueProject) getResultForObject(JiraIssueProject.class, response);
    }

    public List<JiraUser> assignableUserSearch(String projectKey, String query) {
        int startAt = 0;
        int maxResults = 30;
        String baseUrl = getBaseUrl() + "/user/assignable/search?project={1}&maxResults=" + maxResults + "&startAt=" + startAt;
        String url = baseUrl;

        if (StringUtils.isNotBlank(query)) {
            // cloud 加了 username 会报错，报错就用 query
            url = baseUrl + "&username=" + query;
        }

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class, projectKey);
        } catch (Exception e) {
            try {
                // 兼容不同版本查询
                if (StringUtils.isNotBlank(query)) {
                    url = baseUrl + "&query=" + query;
                }
                response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class, projectKey);
            } catch (Exception ex) {
                LogUtil.error(ex);
                return new ArrayList<>();
            }
        }
        return  (List<JiraUser>) getResultForList(JiraUser.class, response);
    }


    public List<JiraUser> allUserSearch(String query) {
        int startAt = 0;
        int maxResults = 30;
        String baseUrl = getBaseUrl() + "/user/search?maxResults=" + maxResults + "&startAt=" + startAt;
        // server 版本没有username报错，报错则加上username
        String url = baseUrl + "&query=" + (StringUtils.isNotBlank(query) ? query : "");
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class);
        } catch (Exception e) {
            try {
                // 兼容不同版本查询
                url = baseUrl + "&username=" + (StringUtils.isNotBlank(query) ? query : "\"\"");
                response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class);
            } catch (Exception ex) {
                LogUtil.error(ex);
                return new ArrayList<>();
            }
        }
        return (List<JiraUser>) getResultForList(JiraUser.class, response);
    }


    public List getDemands(String projectKey, String issueType, int startAt, int maxResults) {
        String jql = getBaseUrl() + "/search?jql=project=" + projectKey + "+AND+issuetype=" + issueType
                + "&maxResults=" + maxResults + "&startAt=" + startAt + "&fields=summary,issuetype";
        ResponseEntity<String> responseEntity = restTemplate.exchange(jql,
                HttpMethod.GET, getAuthHttpEntity(), String.class);
        Map jsonObject = JSON.parseMap(responseEntity.getBody());
        return (List) jsonObject.get("issues");
    }

    public List<JiraField> getFields() {
        ResponseEntity<String> response = restTemplate.exchange(getBaseUrl() + "/field", HttpMethod.GET, getAuthHttpEntity(), String.class);
        return (List<JiraField>) getResultForList(JiraField.class, response);
    }

    public JiraAddIssueResponse addIssue(String body, Map<String, String> filedNameMap) {
        LogUtil.info("addIssue: " + body);
        HttpHeaders headers = getAuthHeader();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = null;
        try {
            response = restTemplate.exchange(getBaseUrl() + "/issue", HttpMethod.POST, requestEntity, String.class);
        } catch (HttpClientErrorException e) {
            handleFieldErrorMsg(filedNameMap, e);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
        return (JiraAddIssueResponse) getResultForObject(JiraAddIssueResponse.class, response);
    }

    /**
     * 创建或者修改的接口返回参数错误
     * 将错误中参数id，替换成参数的名称，方便用户定位
     * @param filedNameMap
     * @param e
     */
    private static void handleFieldErrorMsg(Map<String, String> filedNameMap, HttpClientErrorException e) {
        if (e.getStatusCode().value() == 400 && filedNameMap != null) {
            Map<String, String> fieldNameErrorMap = new HashMap<>();
            try {
                Map<String, String> fieldErrorMap = (Map) JSON.parseMap(e.getResponseBodyAsString()).get("errors");
                fieldErrorMap.forEach((id, msg) ->
                        fieldNameErrorMap.put(filedNameMap.get(id) == null ? id : filedNameMap.get(id), msg));
            } catch (Exception exception) {
                LogUtil.error(exception);
            }
            if (MapUtils.isNotEmpty(fieldNameErrorMap)) {
                MSPluginException.throwException(JSON.toJSONString(fieldNameErrorMap));
            }
        }
        LogUtil.error(e);
        MSPluginException.throwException(e.getMessage());
    }

    public List<JiraTransitionsResponse.Transitions> getTransitions(String issueKey) {
        ResponseEntity<String> response = restTemplate.exchange(getBaseUrl() + "/issue/{1}/transitions", HttpMethod.GET, getAuthHttpEntity(), String.class, issueKey);
        return ((JiraTransitionsResponse) getResultForObject(JiraTransitionsResponse.class, response)).getTransitions();
    }

    public List<JiraSprint> getSprint(String query) {
        String url = getGreenhopperV1BaseUrl() + "/sprint/picker?_=" + System.currentTimeMillis();
        if (StringUtils.isNotBlank(query)) {
            url += "&query=" + query;
        }
        ResponseEntity<String> response = restTemplate.exchange(url,
                HttpMethod.GET, getAuthHttpEntity(), String.class);
        JiraSprintResponse jiraSprintResponse = ((JiraSprintResponse) getResultForObject(JiraSprintResponse.class, response));
        List<JiraSprint> sprints = new ArrayList<>();
        if (!CollectionUtils.isEmpty(jiraSprintResponse.getSuggestions())) {
            sprints = jiraSprintResponse.getSuggestions();
        }
        if (!CollectionUtils.isEmpty(jiraSprintResponse.getAllMatches())) {
            sprints.addAll(jiraSprintResponse.getAllMatches());
        }
        return sprints;
    }

    public List<JiraEpic> getEpics(String queryKey) {
        ResponseEntity<String> response = restTemplate.exchange(getGreenhopperV1BaseUrl() + "/epics?maxResults=300&searchQuery={0}&hideDone=true&_=" + System.currentTimeMillis(),
                HttpMethod.GET, getAuthHttpEntity(), String.class, queryKey);
        List<JiraEpicResponse.EpicLists> epicLists = ((JiraEpicResponse) getResultForObject(JiraEpicResponse.class, response)).getEpicLists();
        if (CollectionUtils.isEmpty(epicLists)) {
            return new ArrayList<>();
        }
        List<JiraEpic> jiraEpics = new ArrayList<>();
        epicLists.forEach(item -> {
            List<JiraEpic> epicNames = item.getEpicNames();
            if (!CollectionUtils.isEmpty(epicNames)) {
                jiraEpics.addAll(epicNames);
            }
        });
        return jiraEpics;
    }

    public List<JiraIssueLink> getIssueLinks(String currentIssueKey, String query) {
        String url = getBaseUrl() + "/issue/picker?showSubTaskParent=true&showSubTasks=true"
                + (StringUtils.isNotEmpty(currentIssueKey) ? "&currentIssueKey=" + currentIssueKey : StringUtils.EMPTY)
                + (StringUtils.isNotEmpty(query) ? "&query=" + query : StringUtils.EMPTY)
                + "&currentJQL=" + URLEncoder.encode(ISSUE_RELATE_FILTER_JQL, StandardCharsets.UTF_8);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), String.class);
        List<JiraIssueLinkResponse.IssueLink> sections = ((JiraIssueLinkResponse) getResultForObject(JiraIssueLinkResponse.class, response)).getSections();
        if (CollectionUtils.isEmpty(sections)) {
            return Collections.emptyList();
        }
        List<JiraIssueLink> issueLinks = new ArrayList<>();
        sections.forEach(section -> {
            List<JiraIssueLink> issues = section.getIssues();
            if (!CollectionUtils.isEmpty(issues)) {
                issueLinks.addAll(issues);
            }
        });
        return issueLinks;
    }

    public List<JiraIssueLinkTypeResponse.IssueLinkType> getIssueLinkType() {
        ResponseEntity<String> response = restTemplate.exchange(getBaseUrl() + "/issueLinkType", HttpMethod.GET, getAuthHttpEntity(), String.class);
        List<JiraIssueLinkTypeResponse.IssueLinkType> issueLinkTypes = ((JiraIssueLinkTypeResponse) getResultForObject(JiraIssueLinkTypeResponse.class, response)).getIssueLinkTypes();
        if (CollectionUtils.isEmpty(issueLinkTypes)) {
            return Collections.emptyList();
        }
        return issueLinkTypes;
    }

    public void linkIssue(JiraIssueLinkRequest request) {
        LogUtil.info("linkIssue: " + request);
        HttpHeaders headers = getAuthHeader();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(JSON.toJSONString(request), headers);
        try {
            restTemplate.exchange(getBaseUrl() + "/issueLink", HttpMethod.POST, requestEntity, String.class);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
    }

    public void deleteIssueLink(String linkId) {
        LogUtil.info("deleteIssueLink: " + linkId);
        try {
            restTemplate.exchange(getBaseUrl() + "/issueLink/" + linkId, HttpMethod.DELETE, getAuthHttpEntity(), String.class);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
    }

    public String getGreenhopperV1BaseUrl() {
        return ENDPOINT + GREENHOPPER_V1_BASE_URL;
    }

    public void updateIssue(String id, String body, Map<String, String> filedNameMap) {
        LogUtil.info("addIssue: " + body);
        HttpHeaders headers = getAuthHeader();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);
        try {
            restTemplate.exchange(getBaseUrl() + "/issue/" + id, HttpMethod.PUT, requestEntity, String.class);
        } catch (HttpClientErrorException e) {
            handleFieldErrorMsg(filedNameMap, e);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
    }

    public void deleteIssue(String id) {
        LogUtil.info("deleteIssue: " + id);
        try {
            restTemplate.exchange(getBaseUrl() + "/issue/" + id, HttpMethod.DELETE, getAuthHttpEntity(), String.class);
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() != 404) {// 404说明jira没有，可以直接删
                MSPluginException.throwException(e.getMessage());
            }
        }
    }

    public void deleteAttachment(String id) {
        LogUtil.info("deleteAttachment: " + id);
        try {
            restTemplate.exchange(getBaseUrl() + "/attachment/" + id, HttpMethod.DELETE, getAuthHttpEntity(), String.class);
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() != 404) {// 404说明jira没有，可以直接删
                MSPluginException.throwException(e.getMessage());
            }
        }
    }


    public void uploadAttachment(String issueKey, File file) {
        HttpHeaders authHeader = getAuthHeader();
        authHeader.add("X-Atlassian-Token", "no-check");
        authHeader.setContentType(MediaType.parseMediaType("multipart/form-data; charset=UTF-8"));

        MultiValueMap<String, Object> paramMap = new LinkedMultiValueMap<>();
        FileSystemResource fileResource = new FileSystemResource(file);
        paramMap.add("file", fileResource);
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(paramMap, authHeader);
        try {
            restTemplate.exchange(getBaseUrl() + "/issue/" + issueKey + "/attachments", HttpMethod.POST, requestEntity, String.class);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
    }

    public void auth() {
        ResponseEntity<String> response = null;
        try {
            response = restTemplate.exchange(getBaseUrl() + "/myself", HttpMethod.GET, getAuthHttpEntity(), String.class);
            if (StringUtils.isBlank(response.getBody()) || (StringUtils.isNotBlank(response.getBody()) && !response.getBody().startsWith("{\"self\""))) {
                MSPluginException.throwException("测试连接失败，请检查Jira地址是否正确");
            }
        } catch (HttpClientErrorException e) {
            if (e.getRawStatusCode() == 401) {
                MSPluginException.throwException("账号名或密码(Token)错误");
            } else {
                LogUtil.error(e.getMessage(), e);
                MSPluginException.throwException(e.getMessage());
            }
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
    }

    protected HttpEntity<MultiValueMap> getAuthHttpEntity() {
        return new HttpEntity<>(getAuthHeader());
    }

    protected HttpHeaders getAuthHeader() {
        HttpHeaders headers;
        if (StringUtils.isNotBlank(AUTH_TYPE) && StringUtils.equals(AUTH_TYPE, "bearer")) {
            headers = getBearHttpHeaders(TOKEN);
        } else {
            headers = getBasicHttpHeaders(USER_NAME, PASSWD);
        }
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip,x-gzip,deflate");
        return headers;
    }

    protected HttpHeaders getAuthJsonHeader() {
        HttpHeaders headers = getAuthHeader();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    protected String getBaseUrl() {
        return ENDPOINT + PREFIX;
    }

    protected String getUrl(String path) {
        return getBaseUrl() + path;
    }

    public void setConfig(JiraConfig config) {
        if (config == null) {
            MSPluginException.throwException("config is null");
        }
        String url = config.getUrl();

        if (StringUtils.isNotBlank(url) && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        ENDPOINT = url;
        USER_NAME = config.getAccount();
        PASSWD = config.getPassword();
        TOKEN = config.getToken();
        AUTH_TYPE = config.getAuthType();
    }

    public JiraIssueListResponse getProjectIssues(Integer startAt, Integer maxResults, String projectKey, String issueType) {
        return getProjectIssues(startAt, maxResults, projectKey, issueType, null);
    }

    public JiraIssueListResponse getProjectIssues(Integer startAt, Integer maxResults, String projectKey, String issueType, String fields) {
        ResponseEntity<String> responseEntity;
        String url = getBaseUrl() + "/search?startAt={1}&maxResults={2}&jql=project={3}+AND+issuetype={4}";
        if (StringUtils.isNotBlank(fields)) {
            url = url + "&fields=" + fields;
        }
        responseEntity = restTemplate.exchange(url,
                HttpMethod.GET, getAuthHttpEntity(), String.class, startAt, maxResults, projectKey, issueType);
        return  (JiraIssueListResponse)getResultForObject(JiraIssueListResponse.class, responseEntity);
    }

    public void getAttachmentContent(String url, Consumer<InputStream> inputStreamHandler) {
        RequestCallback requestCallback = request -> {
            request.getHeaders().addAll(getAuthHeader());
            //定义请求头的接收类型
            request.getHeaders().setAccept(Arrays.asList(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
        };

        restTemplate.execute(url, HttpMethod.GET, requestCallback, clientHttpResponse -> {
            inputStreamHandler.accept(clientHttpResponse.getBody());
            return null;
        });
    }

    public JiraIssueListResponse getProjectIssuesAttachment(Integer startAt, Integer maxResults, String projectKey, String issueType) {
        return getProjectIssues(startAt, maxResults, projectKey, issueType, "attachment");

    }
    public void setTransitions(String jiraKey, JiraTransitionsResponse.Transitions transitions) {
        LogUtil.info("setTransitions: " + transitions);
        Map jsonObject = new LinkedHashMap();
        jsonObject.put("transition", transitions);
        HttpEntity<String> requestEntity = new HttpEntity<>(JSON.toJSONString(jsonObject), getAuthJsonHeader());
        try {
            restTemplate.exchange(getBaseUrl() + "/issue/{1}/transitions", HttpMethod.POST, requestEntity, String.class, jiraKey);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
            MSPluginException.throwException(e.getMessage());
        }
    }

    public ResponseEntity proxyForGet(String path, Class responseEntityClazz) {
        LogUtil.info("jira proxyForGet: " + path);
        String endpoint = this.ENDPOINT;
        try {
            // ENDPOINT 可能会带有前缀，比如 http://xxxx/jira
            // 这里去掉 /jira，再拼接图片路径path
            URI uri = new URI(this.ENDPOINT);
            endpoint = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
        } catch (URISyntaxException e) {
            LogUtil.error(e);
        }
        String url = endpoint + path;
        validateProxyUrl(url, "/secure/attachment", "/attachment/content");
        return restTemplate.exchange(url, HttpMethod.GET, getAuthHttpEntity(), responseEntityClazz);
    }

    public List<JiraStatusResponse> getStatus(String jiraKey) {
        ResponseEntity<String> response = restTemplate.exchange(getBaseUrl() + "/project/"+jiraKey+"/statuses", HttpMethod.GET, getAuthHttpEntity(), String.class);
        return (List<JiraStatusResponse>)getResultForList(JiraStatusResponse.class, response);
    }
}
