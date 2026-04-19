# 模块化重构文档

## 1. 重构概述

### 1.1 重构目标

将当前单模块项目重构为四个独立的Maven模块，实现：
- 清晰的模块边界和职责划分
- 合理的依赖关系
- 接口暴露与实现隐藏
- 便于独立演进和复用

### 1.2 模块划分

| 模块名 | artifactId | 包路径 | 职责 |
|--------|------------|--------|------|
| common | graph-query-common | com.fangyang.common | 通用工具类 |
| metadata | graph-query-metadata | com.fangyang.metadata | 元数据管理（点、边schema信息） |
| datasource | graph-query-datasource | com.fangyang.datasource | 多数据源请求（TuGraph、外部数据源） |
| federatedquery | graph-query-engine | com.fangyang.federatedquery | 联邦查询引擎核心逻辑 |

### 1.3 版本管理

所有模块统一版本：`1.0.0-SNAPSHOT`

---

## 2. 依赖关系

```
                    common（无依赖）
                        ↑
            ┌───────────┴───────────┐
            │                       │
        metadata              datasource
    （暴露接口，实现隐藏）    （暴露接口，实现隐藏）
            ↑                       ↑
            └───────────┬───────────┘
                        │
                federatedquery
            （依赖所有其他模块）
```

### 2.1 依赖规则

| 模块 | 可依赖 | 不可依赖 |
|------|--------|----------|
| common | 无 | 所有模块 |
| metadata | common | datasource, federatedquery |
| datasource | common | metadata, federatedquery |
| federatedquery | common, metadata, datasource | 无 |

---

## 3. 模块详细设计

### 3.1 common模块

#### 包含内容

| 类名 | 说明 |
|------|------|
| JsonUtil | JSON工具类 |

#### 包结构

```
graph-query-common/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── fangyang/
    │               └── common/
    │                   └── JsonUtil.java
    └── test/
        └── java/
            └── com/
                └── fangyang/
                    └── common/
                        └── JsonUtilTest.java
```

---

### 3.2 metadata模块

#### 包含内容

| 类名 | 类型 | 可见性 | 说明 |
|------|------|--------|------|
| DataSourceMetadata | 类 | public | 数据源元数据 |
| DataSourceType | 枚举 | public | 数据源类型 |
| LabelMetadata | 类 | public | 标签元数据 |
| VirtualEdgeBinding | 类 | public | 虚边绑定信息 |
| MetadataRegistrar | 接口 | public | 元数据注册接口（暴露） |
| MetadataQueryService | 接口 | public | 元数据查询接口（暴露） |
| MetadataRegistryImpl | 类 | package-private | 元数据注册实现（隐藏） |

#### 暴露接口设计

```java
package com.fangyang.metadata;

/**
 * 元数据注册接口
 * 用于注册数据源、虚边、标签等元数据
 */
public interface MetadataRegistrar {
    void registerDataSource(DataSourceMetadata metadata);
    void registerVirtualEdge(VirtualEdgeBinding binding);
    void registerLabel(LabelMetadata label);
    void clear();
}
```

```java
package com.fangyang.metadata;

import java.util.Optional;

/**
 * 元数据查询接口
 * 用于查询元数据信息
 */
public interface MetadataQueryService {
    Optional<DataSourceMetadata> getDataSource(String name);
    Optional<VirtualEdgeBinding> getVirtualEdgeBinding(String edgeType);
    Optional<LabelMetadata> getLabel(String label);
    boolean isVirtualEdge(String edgeType);
    boolean isVirtualLabel(String label);
    String getDataSourceForEdge(String edgeType);
    String getDataSourceForLabel(String label);
    String getTargetLabelForEdge(String edgeType);
}
```

#### 包结构

```
graph-query-metadata/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── fangyang/
    │               └── metadata/
    │                   ├── DataSourceMetadata.java
    │                   ├── DataSourceType.java
    │                   ├── LabelMetadata.java
    │                   ├── VirtualEdgeBinding.java
    │                   ├── MetadataRegistrar.java
    │                   ├── MetadataQueryService.java
    │                   └── MetadataRegistryImpl.java
    └── test/
        └── java/
            └── com/
                └── fangyang/
                    └── metadata/
                        └── MetadataRegistryImplTest.java
```

---

### 3.3 datasource模块

#### 包含内容

| 类名 | 类型 | 可见性 | 说明 |
|------|------|--------|------|
| TuGraphConfig | 类 | public | TuGraph配置 |
| TuGraphConnector | 接口 | public | TuGraph连接器接口 |
| TuGraphConnectorImpl | 类 | package-private | TuGraph连接器实现（隐藏） |
| DataSourceAdapter | 接口 | public | 数据源适配器接口（暴露） |
| TuGraphAdapterImpl | 类 | package-private | TuGraph适配器实现（隐藏） |
| DataSourceQueryParams | 类 | public | 外部数据源查询参数DTO |

#### 暴露接口设计

```java
package com.fangyang.datasource;

import org.neo4j.driver.Record;
import java.util.List;
import java.util.Map;

/**
 * TuGraph连接器接口
 */
public interface TuGraphConnector {
    List<Record> executeQuery(String cypher);
    List<Record> executeQuery(String cypher, Object... parameters);
    void close();
    boolean isConnected();
    TuGraphConfig getConfig();
}
```

```java
package com.fangyang.datasource;

import org.neo4j.driver.Record;
import java.util.List;
import java.util.Map;

/**
 * 数据源适配器接口
 * 统一封装TuGraph和外部数据源查询
 */
public interface DataSourceAdapter {
    String getDataSourceType();
    String getDataSourceName();
    boolean isHealthy();
    
    /**
     * TuGraph查询 - 返回原始Record
     */
    List<Record> executeTuGraphQuery(String cypher);
    List<Record> executeTuGraphQuery(String cypher, Map<String, Object> params);
    
    /**
     * 外部数据源查询 - 返回原始Map列表
     */
    List<Map<String, Object>> executeExternalQuery(DataSourceQueryParams params);
}
```

```java
package com.fangyang.datasource;

import lombok.Data;
import java.util.*;

/**
 * 外部数据源查询参数
 */
@Data
public class DataSourceQueryParams {
    private String dataSource;
    private String operator;
    private String targetLabel;
    private Map<String, Object> inputMapping = new HashMap<>();
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private String outputIdField;
    private List<String> outputVariables = new ArrayList<>();
    private List<String> outputFields = new ArrayList<>();
    private Map<String, Object> filters = new HashMap<>();
    private Map<String, Object> parameters = new HashMap<>();
}
```

#### 包结构

```
graph-query-datasource/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── fangyang/
    │               └── datasource/
    │                   ├── TuGraphConfig.java
    │                   ├── TuGraphConnector.java
    │                   ├── TuGraphConnectorImpl.java
    │                   ├── DataSourceAdapter.java
    │                   ├── TuGraphAdapterImpl.java
    │                   └── DataSourceQueryParams.java
    └── test/
        └── java/
            └── com/
                └── fangyang/
                    └── datasource/
                        ├── TuGraphConnectorImplTest.java
                        └── TuGraphAdapterImplTest.java
```

---

### 3.4 federatedquery模块

#### 包含内容

| 包名 | 说明 |
|------|------|
| ast | 抽象语法树节点 |
| parser | Cypher解析器 |
| plan | 执行计划 |
| rewriter | 查询重写器 |
| executor | 联邦执行器 |
| aggregator | 结果聚合器 |
| sdk | SDK入口 |
| exception | 异常类 |
| util | 工具类 |
| grammar | ANTLR4生成的类 |

#### 特殊类

| 类名 | 说明 |
|------|------|
| model/GraphEntity | 图实体（点、边）领域模型 |
| model/QueryResult | 查询结果领域模型 |
| RecordConverter | Record转换器 |

#### 包结构

```
graph-query-engine/
├── pom.xml
└── src/
    ├── main/
    │   ├── antlr4/
    │   │   └── com/
    │   │       └── fangyang/
    │   │           └── federatedquery/
    │   │               └── grammar/
    │   │                   └── Lcypher.g4
    │   ├── java/
    │   │   └── com/
    │   │       └── fangyang/
    │   │           └── federatedquery/
    │   │               ├── ast/
    │   │               ├── parser/
    │   │               ├── plan/
    │   │               ├── rewriter/
    │   │               ├── executor/
    │   │               ├── aggregator/
    │   │   │               ├── sdk/
│   │   │               ├── exception/
│   │   │               ├── model/           # 领域模型包
│   │   │               │   ├── GraphEntity.java
│   │   │               │   └── QueryResult.java
│   │   │               ├── util/
    │   └── resources/
    │       ├── spring/
    │       │   └── applicationContext.xml
    │       └── logback.xml
    └── test/
        └── java/
            └── com/
                └── fangyang/
                    └── federatedquery/
                        ├── parser/
                        ├── rewriter/
                        ├── executor/
                        ├── aggregator/
                        ├── sdk/
                        └── e2e/
```

---

## 4. Maven多模块配置

### 4.1 父POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.fangyang</groupId>
    <artifactId>graph-query-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>Graph Query Parent</name>
    <description>Federated Graph Query Engine - Parent POM</description>

    <modules>
        <module>graph-query-common</module>
        <module>graph-query-metadata</module>
        <module>graph-query-datasource</module>
        <module>graph-query-engine</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring.version>6.1.6</spring.version>
        <antlr4.version>4.13.1</antlr4.version>
        <caffeine.version>3.1.8</caffeine.version>
        <jackson.version>2.17.0</jackson.version>
        <junit.version>5.10.2</junit.version>
        <mockito.version>5.11.0</mockito.version>
        <slf4j.version>2.0.12</slf4j.version>
        <logback.version>1.5.4</logback.version>
        <neo4j.driver.version>4.4.18</neo4j.driver.version>
        <lombok.version>1.18.30</lombok.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- 内部模块 -->
            <dependency>
                <groupId>com.fangyang</groupId>
                <artifactId>graph-query-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fangyang</groupId>
                <artifactId>graph-query-metadata</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fangyang</groupId>
                <artifactId>graph-query-datasource</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- Spring -->
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-context</artifactId>
                <version>${spring.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-web</artifactId>
                <version>${spring.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-test</artifactId>
                <version>${spring.version}</version>
                <scope>test</scope>
            </dependency>

            <!-- ANTLR4 -->
            <dependency>
                <groupId>org.antlr</groupId>
                <artifactId>antlr4-runtime</artifactId>
                <version>${antlr4.version}</version>
            </dependency>

            <!-- Caffeine -->
            <dependency>
                <groupId>com.github.ben-manes.caffeine</groupId>
                <artifactId>caffeine</artifactId>
                <version>${caffeine.version}</version>
            </dependency>

            <!-- Jackson -->
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-core</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-annotations</artifactId>
                <version>${jackson.version}</version>
            </dependency>

            <!-- Logging -->
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
            </dependency>
            <dependency>
                <groupId>ch.qos.logback</groupId>
                <artifactId>logback-classic</artifactId>
                <version>${logback.version}</version>
            </dependency>

            <!-- Lombok -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
                <scope>provided</scope>
            </dependency>

            <!-- Neo4j Driver -->
            <dependency>
                <groupId>org.neo4j.driver</groupId>
                <artifactId>neo4j-java-driver</artifactId>
                <version>${neo4j.driver.version}</version>
            </dependency>

            <!-- Test -->
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-core</artifactId>
                <version>${mockito.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-junit-jupiter</artifactId>
                <version>${mockito.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.12.1</version>
                    <configuration>
                        <source>${java.version}</source>
                        <target>${java.version}</target>
                        <encoding>${project.build.sourceEncoding}</encoding>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

### 4.2 common模块POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fangyang</groupId>
        <artifactId>graph-query-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>graph-query-common</artifactId>
    <packaging>jar</packaging>

    <name>Graph Query Common</name>
    <description>Common utilities for Graph Query Engine</description>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.3 metadata模块POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fangyang</groupId>
        <artifactId>graph-query-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>graph-query-metadata</artifactId>
    <packaging>jar</packaging>

    <name>Graph Query Metadata</name>
    <description>Metadata management for Graph Query Engine</description>

    <dependencies>
        <dependency>
            <groupId>com.fangyang</groupId>
            <artifactId>graph-query-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.4 datasource模块POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fangyang</groupId>
        <artifactId>graph-query-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>graph-query-datasource</artifactId>
    <packaging>jar</packaging>

    <name>Graph Query DataSource</name>
    <description>Data source adapters for Graph Query Engine</description>

    <dependencies>
        <dependency>
            <groupId>com.fangyang</groupId>
            <artifactId>graph-query-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.neo4j.driver</groupId>
            <artifactId>neo4j-java-driver</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.5 federatedquery模块POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fangyang</groupId>
        <artifactId>graph-query-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>graph-query-engine</artifactId>
    <packaging>jar</packaging>

    <name>Graph Query Engine</name>
    <description>Federated Graph Query Engine SDK</description>

    <dependencies>
        <!-- 内部模块 -->
        <dependency>
            <groupId>com.fangyang</groupId>
            <artifactId>graph-query-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fangyang</groupId>
            <artifactId>graph-query-metadata</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fangyang</groupId>
            <artifactId>graph-query-datasource</artifactId>
        </dependency>

        <!-- Spring -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>

        <!-- ANTLR4 -->
        <dependency>
            <groupId>org.antlr</groupId>
            <artifactId>antlr4-runtime</artifactId>
        </dependency>

        <!-- Caffeine -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>

        <!-- Neo4j Driver -->
        <dependency>
            <groupId>org.neo4j.driver</groupId>
            <artifactId>neo4j-java-driver</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.antlr</groupId>
                <artifactId>antlr4-maven-plugin</artifactId>
                <version>${antlr4.version}</version>
                <executions>
                    <execution>
                        <id>antlr</id>
                        <goals>
                            <goal>antlr4</goal>
                        </goals>
                        <configuration>
                            <visitor>true</visitor>
                            <listener>true</listener>
                            <outputDirectory>${project.build.directory}/generated-sources/antlr4/com/fangyang/federatedquery/grammar</outputDirectory>
                            <arguments>
                                <argument>-package</argument>
                                <argument>com.fangyang.federatedquery.grammar</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.5.0</version>
                <executions>
                    <execution>
                        <id>add-source</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>add-source</goal>
                        </goals>
                        <configuration>
                            <sources>
                                <source>${project.build.directory}/generated-sources/antlr4</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 5. Spring配置

### 5.1 配置文件位置

在 `graph-query-engine` 模块的 `src/main/resources/spring/` 目录下创建统一的Spring配置文件。

### 5.2 applicationContext.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xmlns:context="http://www.springframework.org/schema/context"
       xsi:schemaLocation="
           http://www.springframework.org/schema/beans
           http://www.springframework.org/schema/beans/spring-beans.xsd
           http://www.springframework.org/schema/context
           http://www.springframework.org/schema/context/spring-context.xsd">

    <!-- 扫描所有模块的组件 -->
    <context:component-scan base-package="com.fangyang.common"/>
    <context:component-scan base-package="com.fangyang.metadata"/>
    <context:component-scan base-package="com.fangyang.datasource"/>
    <context:component-scan base-package="com.fangyang.federatedquery"/>

</beans>
```

---

## 6. 包名变更映射

| 原包名 | 新包名 |
|--------|--------|
| com.federatedquery.util.JsonUtil | com.fangyang.common.JsonUtil |
| com.federatedquery.metadata.* | com.fangyang.metadata.* |
| com.federatedquery.connector.* | com.fangyang.datasource.* |
| com.federatedquery.adapter.DataSourceAdapter | com.fangyang.datasource.DataSourceAdapter |
| com.federatedquery.adapter.TuGraphAdapterImpl | com.fangyang.datasource.TuGraphAdapterImpl |
| com.federatedquery.adapter.GraphEntity | com.fangyang.federatedquery.model.GraphEntity (位于 model 包，图实体领域模型) |
| com.federatedquery.adapter.QueryResult | com.fangyang.federatedquery.model.QueryResult (位于 model 包，查询结果领域模型) |
| com.federatedquery.grammar.* | com.fangyang.federatedquery.grammar.* |
| com.federatedquery.* (其他) | com.fangyang.federatedquery.* |

---

## 7. 实施步骤

### 7.1 准备阶段

1. 创建新的Git分支：`feature/module-refactor`
2. 备份当前代码

### 7.2 结构创建阶段

1. 创建父POM
2. 创建四个子模块目录结构
3. 创建各模块的pom.xml

### 7.3 代码迁移阶段

1. 迁移common模块代码
2. 迁移metadata模块代码
   - 拆分MetadataRegistry接口
   - 调整实现类可见性
3. 迁移datasource模块代码
   - 调整接口方法签名
   - 调整实现类可见性
4. 迁移federatedquery模块代码
   - 更新所有import语句
   - 迁移ANTLR4语法文件

### 7.4 测试迁移阶段

1. 按模块拆分测试代码
2. 更新测试中的import语句
3. 调整测试中的包名引用

### 7.5 验证阶段

1. 执行 `mvn clean compile` 验证编译
2. 执行 `mvn test` 验证测试
3. 执行 `mvn package` 验证打包

### 7.6 清理阶段

1. 删除旧的src目录
2. 删除GraphQueryEngineConfiguration.java
3. 更新AGENTS.md文档

---

## 8. 风险与注意事项

### 8.1 编译顺序

由于ANTLR4生成的代码在federatedquery模块，编译顺序必须为：
1. common
2. metadata
3. datasource
4. federatedquery

### 8.2 IDE兼容性

重构后需要：
1. 在IDE中重新导入Maven项目
2. 确保IDE正确识别generated-sources目录

### 8.3 测试覆盖

重构后需要验证：
1. 所有单元测试通过
2. 所有E2E测试通过
3. 测试覆盖率不低于重构前

---

## 9. 附录

### 9.1 目录结构总览

```
GraphQueryEngine/
├── pom.xml                          # 父POM
├── graph-query-common/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/fangyang/common/
│       └── test/java/com/fangyang/common/
├── graph-query-metadata/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/fangyang/metadata/
│       └── test/java/com/fangyang/metadata/
├── graph-query-datasource/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/fangyang/datasource/
│       └── test/java/com/fangyang/datasource/
├── graph-query-engine/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── antlr4/com/fangyang/federatedquery/grammar/
│       │   ├── java/com/fangyang/federatedquery/
│       │   └── resources/
│       │       ├── spring/applicationContext.xml
│       │       └── logback.xml
│       └── test/java/com/fangyang/federatedquery/
└── docs/
    └── refactor/
        └── module/
            └── README.md           # 本文档
```
