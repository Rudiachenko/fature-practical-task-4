# Java Coding Conventions and Best Practices

## Naming Conventions

### Classes and Interfaces

- Use PascalCase for class and interface names
- Class names should be nouns (e.g., `Customer`, `OrderProcessor`)
- Interface names should describe behavior (e.g., `Runnable`, `Serializable`)
- Avoid prefixes like "I" for interfaces

### Methods

- Use camelCase for method names
- Method names should be verbs or verb phrases (e.g., `calculateTotal()`, `isValid()`)
- Boolean methods should start with `is`, `has`, `can`, or `should`
- Getters and setters should follow JavaBean conventions (`getName()`, `setName()`)

### Variables

- Use camelCase for variable names
- Variable names should be descriptive and meaningful
- Avoid single-letter names except for loop counters (`i`, `j`, `k`)
- Constants should be UPPER_CASE with underscores (e.g., `MAX_SIZE`, `DEFAULT_TIMEOUT`)

### Packages

- Use lowercase for package names
- Follow reverse domain naming (e.g., `com.company.project.module`)
- Avoid underscores or mixed case

## Code Structure

### Class Organization

1. Package statement
2. Import statements (organized and no wildcards)
3. Class/interface documentation
4. Class declaration
5. Static variables (public, protected, private)
6. Instance variables (public, protected, private)
7. Constructors
8. Methods (grouped by functionality)
9. Nested classes
10. Never use prefix "Code" for classes

### Method Design

- Keep methods short and focused (ideally under 20 lines)
- Follow Single Responsibility Principle
- Limit method parameters (preferably no more than 3-4)
- Use method overloading judiciously

### Comments and Documentation

- Use JavaDoc for all public classes, interfaces, and methods
- Include `@param`, `@return`, and `@throws` tags in JavaDoc
- Use inline comments sparingly, only for complex logic
- Avoid obvious comments; let code be self-documenting
- Keep comments up-to-date with code changes

## Best Practices

### Object-Oriented Principles

- Favor composition over inheritance
- Program to interfaces, not implementations
- Use encapsulation (private fields with public getters/setters)
- Follow SOLID principles
- Apply design patterns appropriately

### Exception Handling

- Never catch and ignore exceptions silently
- Use specific exception types, not generic `Exception`
- Don't use exceptions for control flow
- Always clean up resources in `finally` blocks or use try-with-resources
- Create custom exceptions for domain-specific errors

### Collections and Generics

- Use generics to ensure type safety
- Prefer interfaces over implementations (`List<String>` not `ArrayList<String>`)
- Use appropriate collection types (Set for uniqueness, List for order, Map for key-value)
- Consider immutable collections when possible
- Use Java Streams API for collection operations when appropriate

### Concurrency

- Minimize mutable shared state
- Use thread-safe collections from `java.util.concurrent`
- Prefer `ExecutorService` over creating threads manually
- Always document thread-safety guarantees
- Use synchronized blocks judiciously

### Modern Java Features

- Use Optional to avoid null pointer exceptions
- Leverage lambda expressions and functional interfaces
- Use Stream API for data processing
- Utilize try-with-resources for AutoCloseable resources
- Use var for local variables when type is obvious (Java 10+)
- Consider records for data transfer objects (Java 14+)
- Use text blocks for multi-line strings (Java 13+)

### Spring Framework Specific

- Use constructor injection over field injection
- Prefer `@RequiredArgsConstructor` from Lombok for dependency injection
- Use `@Service`, `@Repository`, `@Controller` annotations appropriately
- Leverage Spring Boot auto-configuration
- Use `@Value` or `@ConfigurationProperties` for externalized configuration
- Follow RESTful conventions for API endpoints

## Code Quality

### Performance

- Avoid premature optimization
- Use StringBuilder for string concatenation in loops
- Cache expensive computations when appropriate
- Use lazy initialization for heavy objects
- Profile before optimizing

### Testing

- Write unit tests for all business logic
- Follow AAA pattern (Arrange, Act, Assert)
- Use meaningful test names that describe the scenario
- Aim for high code coverage but focus on quality
- Use mocking frameworks (Mockito) appropriately
- Write integration tests for critical paths

### Security

- Validate all input from external sources
- Use parameterized queries to prevent SQL injection
- Don't log sensitive information
- Use secure random number generation for security-critical operations
- Follow principle of least privilege

### Maintainability

- Keep classes and methods small and focused
- Avoid deep nesting (max 3-4 levels)
- Use meaningful variable and method names
- Remove dead code and unused imports
- Avoid magic numbers; use named constants
- Follow DRY (Don't Repeat Yourself) principle

## Common Anti-Patterns to Avoid

- God classes (classes that do too much)
- Circular dependencies
- Primitive obsession (use value objects)
- Feature envy (methods using another class's data extensively)
- Long parameter lists
- Tight coupling between classes
- Overuse of static methods
- Returning null (use Optional instead)

