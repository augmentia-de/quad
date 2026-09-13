# QUAD Java Examples

## ResearchAgent

The `ResearchAgent` demonstrates how to create an agent that:
1. Performs web searches using `WebSearchTool`
2. Reads files from the workspace using `ReadFileTool` (limited to 50 lines)

### Running the Example

```bash
# Set workspace directory (optional, defaults to /work/quad)
export QUAD_WORKSPACE=/tmp/quad-workspace

# Build the project first
cd quad
mvn clean install -DskipTests

# Run the example
java -Dquad.workspace=/tmp/quad-workspace \
  -cp "quad-examples/target/classes:$(mvn dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout)" \
  de.augmentia.quad.examples.ResearchAgentMain \
  "Java programming" "docs/guide.txt"
```

### Using from Java Code

```java
// Initialize agent
ResearchAgent agent = new ResearchAgent();

// Configure workspace
agent.setWorkspaceResolver(new WorkspaceResolver());
// Or set via system property: -Dquad.workspace=/tmp/quad

// Use built-in tools directly
String content = agent.readFile("docs/guide.txt");

// Web search
String results = agent.websearch("Java programming");

// Combined operation
String combined = agent.researchAndReadFile("Java 17 features", "docs/java17.txt");

// Access tool registry for advanced usage
Map<String, ToolMethod> tools = agent.getToolRegistry();
```

> **Note:** The web search requires a configured search API endpoint (e.g., SearXNG). Set via:
> - System property: `-Dquad.tools.websearch.api-url=https://api.example.com/search`
> - Environment variable: `QUAD_TOOLS_WEBSEARCH_API_URL`

### Sample Parameters

#### Command Line Arguments
```bash
# topic         filePath
./run-research-agent.sh "Java programming" "docs/guide.txt"

# Complex topic with quotes
./run-research-agent.sh "Spring Boot microservices" "articles/spring.md"

# Technical documentation
./run-research-agent.sh "React hooks patterns" "docs/react-patterns.txt"

# With custom workspace
./run-research-agent.sh -w /tmp/quad "Java programming" "docs/guide.txt"
```

#### File Read Parameters
```java
// Basic file read (max 50 lines)
agent.readFile("docs/guide.txt")

// Custom offset and limit
agent.readFile("docs/guide.txt", 10, 100)  // Lines 10-110

// Read entire file
agent.readFile("docs/full-document.txt", 1, 10000)  // Custom max limit
```

#### Web Search Parameters
```java
// Basic search
agent.websearch("Java 17 features")

// Via researchAndReadFile (combined)
agent.researchAndReadFile("Java 17 features", "docs/java17.txt")
```

#### Configuration Properties
```
-Dquad.workspace=/tmp/quad                    # Workspace base directory
-Dquad.tools.websearch.api-url=https://...    # Search API endpoint
-Dquad.tools.websearch.timeout-ms=5000        # Timeout in milliseconds
```

#### Environment Variables
```
QUAD_WORKSPACE=/tmp/quad                      # Workspace base directory
QUAD_TOOLS_WEBSEARCH_API_URL=https://...      # Search API endpoint
QUAD_TOOLS_WEBSEARCH_TIMEOUT_MS=5000          # Timeout in milliseconds
```