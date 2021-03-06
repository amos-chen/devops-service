package io.choerodon.devops.api.vo.kubernetes.certification;

import io.swagger.annotations.ApiModelProperty;

import java.util.Objects;

/**
 * Created by n!Ck
 * Date: 2018/8/20
 * Time: 16:57
 * Description:
 */
public class CertificationMetadata {
    @ApiModelProperty("名称")
    private String name;
    @ApiModelProperty("命名空间")
    private String namespace;

    public CertificationMetadata() {
    }

    public CertificationMetadata(String name, String namespace) {
        this.name = name;
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CertificationMetadata)) {
            return false;
        }
        CertificationMetadata that = (CertificationMetadata) o;
        return Objects.equals(getName(), that.getName())
                && Objects.equals(getNamespace(), that.getNamespace());
    }

    @Override
    public int hashCode() {

        return Objects.hash(getName(), getNamespace());
    }
}
