# AI Agents in Auto Klick Development

This `agents.md` file documents the roles and interactions of the AI agents involved in the development of the Auto Klick application.

## Roles

### User (Primary Developer)
The user acts as the primary developer, initiating requests, providing high-level requirements, and guiding the overall direction of the project. They are responsible for defining new features, identifying areas for improvement, and reviewing the changes proposed by the AI assistant.

### AI Assistant (Expert Developer)
I, the AI assistant, serve as an expert developer. My role is to understand the user's requests, break them down into actionable steps, and implement the necessary code changes. I leverage a suite of specialized tools to:
-   **Read and Write Files**: Access and modify the project's source code and configuration files.
-   **Search Code**: Locate declarations, usages, and specific code patterns across the project.
-   **Analyze Code**: Identify potential issues, errors, and warnings.
-   **Manage Version Control**: Interact with Git for tasks like listing roots (though direct commits are handled by the user).

My goal is to provide concise, helpful, and modern development practices, while maintaining consistency with the existing codebase.

## Interaction Model

The development process follows a conversational, iterative model:

1.  **User Request**: The user describes a desired feature, bug fix, or refactoring task in natural language.
2.  **AI Analysis**: I analyze the request, determine the necessary information, and formulate a plan.
3.  **Tool Execution**: I execute a single tool call (e.g., `read_file`, `find_declaration`, `write_file`) to gather information or make a change.
4.  **Response & Adaptation**: I interpret the tool's output and adapt my plan accordingly. This might involve further tool calls, asking clarifying questions to the user (if necessary), or proposing code changes.
5.  **Code Modification**: When ready, I use `write_file` to apply changes to the codebase. I avoid direct shell commands for file modification to ensure safety and IDE synchronization.
6.  **Confirmation/Iteration**: After making changes, I inform the user of the completed task or any further steps. The user can then review, test, and provide new requests.

## Benefits of this Approach

-   **Accelerated Development**: AI agents can quickly implement well-defined tasks, reducing development time.
-   **Reduced Boilerplate**: Automation of repetitive coding patterns and structural changes.
-   **Consistency**: Adherence to established coding styles and architectural patterns.
-   **Knowledge Augmentation**: The AI assistant brings expert knowledge and best practices to the development process.
-   **Focus on High-Level Design**: The primary developer can concentrate on architectural decisions and complex logic, offloading implementation details.
-   **Learning and Documentation**: The interaction log serves as a detailed record of development decisions and changes.
