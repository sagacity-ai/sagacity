# Guide 1: Project Structure & Build System

## The Module Layout

```
sagacity/
├── pom.xml                          ← Parent POM (version control, shared config)
├── sagacity-core/                   ← Pure Java 17. Zero framework dependencies.
├── sagacity-spring-ai/              ← Spring AI integration (ToolCallback decorator)
├── sagacity-spring-boot-starter/    ← Auto-config for Spring Boot apps
└── sagacity-examples/               ← Demo applications
```

## Why Multiple Modules?

Each module boundary enforces a **dependency rule**:

| Module | Depends on | Purpose |
|--------|-----------|---------|
| `sagacity-core` | Nothing (pure Java 17) | Journal, annotations, hash chain, compensation runner |
| `sagacity-spring-ai` | core + `spring-ai-model` | ToolCallback decorator, saga scope, facade |
| `sagacity-spring-boot-starter` | spring-ai + Spring Boot | Zero-config auto-wiring, REST endpoints |
| `sagacity-examples` | spring-ai module | Runnable demos |

**The key architectural decision:** `sagacity-core` has NO Spring dependency. The journal, hash chain, compensation runner, annotations — all pure Java. This means a future LangChain4j or Quarkus adapter only needs `sagacity-core`, not the entire Spring AI stack.

```
sagacity-core  ←──  sagacity-spring-ai  ←──  sagacity-spring-boot-starter
     ↑                      ↑
     │                      │
  (pure Java)         sagacity-examples
```

## The Parent POM

### Packaging type

```xml
<packaging>pom</packaging>
```

This tells Maven "don't produce a jar." The parent's job is to:
1. Define shared configuration
2. List modules to build together
3. Pin versions in one place

### Properties — single source of truth

```xml
<properties>
    <maven.compiler.release>17</maven.compiler.release>
    <spring-ai.version>2.0.0</spring-ai.version>
    <junit.version>5.11.4</junit.version>
</properties>
```

All version numbers live here. Change Spring AI from 2.0.0 to 2.1.0? One line, not four files.

### dependencyManagement — version locking without forcing

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.sagacity</groupId>
            <artifactId>sagacity-core</artifactId>
            <version>${project.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

`dependencyManagement` doesn't ADD dependencies to child modules. It says: "IF a child declares this dependency, use THIS version." Children then write:

```xml
<dependency>
    <groupId>dev.sagacity</groupId>
    <artifactId>sagacity-core</artifactId>
    <!-- no <version> needed — parent controls it -->
</dependency>
```

### The compiler plugin — why `<parameters>true</parameters>`

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <parameters>true</parameters>
    </configuration>
</plugin>
```

Spring AI's `@Tool` methods need parameter names at runtime to bind JSON arguments to method parameters. Without this flag, Java bytecode loses parameter names (`productId` becomes `arg0`). With it, reflection can read the real names.

Without this, `@Tool` methods silently fail to bind arguments.

## Verifying the Architecture

In IntelliJ's Maven tool window, expand each module's Dependencies node:
- `sagacity-core` → only junit + assertj (test scope)
- `sagacity-spring-ai` → sagacity-core + spring-ai-model
- `sagacity-spring-boot-starter` → both internal modules + Spring Boot

If you accidentally import a Spring class in `sagacity-core`, the build fails. The module boundaries enforce architecture.
