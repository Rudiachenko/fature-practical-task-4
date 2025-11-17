# Python Coding Conventions and Best Practices (PEP 8)

## Naming Conventions

### Classes

- Use PascalCase for class names (e.g., `CustomerAccount`, `DataProcessor`)
- Class names should be nouns
- Avoid underscores in class names

### Functions and Methods

- Use snake_case for function and method names (e.g., `calculate_total()`, `get_user_data()`)
- Function names should be verbs or verb phrases
- Boolean functions should start with `is_`, `has_`, `can_`, or `should_`
- Private methods should start with single underscore (e.g., `_internal_method()`)
- Name mangling with double underscore is rarely needed

### Variables

- Use snake_case for variable names (e.g., `user_count`, `max_retries`)
- Constants should be UPPER_CASE with underscores (e.g., `MAX_SIZE`, `DEFAULT_TIMEOUT`)
- Avoid single-letter names except for loop counters and mathematical operations
- Use descriptive names that convey meaning

### Modules and Packages

- Use short, lowercase names for modules
- Avoid underscores if possible, but acceptable if improves readability
- Package names should be lowercase without underscores

## Code Layout

### Indentation

- Use 4 spaces per indentation level (never tabs)
- Continuation lines should align wrapped elements vertically or use hanging indent

### Line Length

- Limit all lines to maximum 79 characters for code
- Limit docstrings/comments to 72 characters
- Use implicit line continuation inside parentheses, brackets, or braces

### Blank Lines

- Surround top-level functions and class definitions with two blank lines
- Surround method definitions inside classes with one blank line
- Use blank lines sparingly inside functions to indicate logical sections

### Imports

- Put imports at the top of the file, after module docstring
- Group imports in this order:
  1. Standard library imports
  2. Related third-party imports
  3. Local application/library imports
- Use separate lines for each import
- Avoid wildcard imports (`from module import *`)
- Use absolute imports over relative imports when possible

### Whitespace

- Avoid extraneous whitespace inside parentheses, brackets, or braces
- No whitespace before comma, semicolon, or colon
- Use whitespace around operators (except in keyword arguments)
- Don't use spaces around `=` in keyword arguments or default parameters

## Documentation

### Docstrings

- Write docstrings for all public modules, functions, classes, and methods
- Use triple double quotes `"""` for docstrings
- Follow Google, NumPy, or Sphinx docstring style consistently
- Include:
  - Brief description of what the function/class does
  - Args: description of parameters
  - Returns: description of return value
  - Raises: exceptions that may be raised

### Comments

- Write comments in complete sentences
- Update comments when code changes
- Use inline comments sparingly
- Avoid obvious comments; code should be self-documenting
- Use TODO comments for temporary or incomplete code

## Best Practices

### Functions and Methods

- Keep functions short and focused (ideally under 20-30 lines)
- Follow Single Responsibility Principle
- Limit function parameters (preferably 3-4, use *args/**kwargs if needed)
- Use default argument values appropriately
- Avoid mutable default arguments (use None and initialize inside)

### Classes and Objects

- Use `__init__` to initialize instance variables
- Use `@property` decorator for getters/setters
- Implement `__str__` and `__repr__` for meaningful string representations
- Use `@classmethod` for alternative constructors
- Use `@staticmethod` for utility functions that don't need instance/class
- Follow composition over inheritance when appropriate

### Error Handling

- Use specific exception types, not bare `except:`
- Create custom exceptions by inheriting from Exception
- Use `try`/`except`/`else`/`finally` blocks appropriately
- Don't silence exceptions without good reason
- Use context managers (`with` statement) for resource management

### Data Structures

- Use list comprehensions for simple transformations
- Use generator expressions for large datasets
- Use dictionaries for key-value lookups
- Use sets for uniqueness and membership testing
- Use tuples for immutable sequences
- Consider `collections` module for specialized data structures
- Use `dataclasses` for data containers (Python 3.7+)

### Modern Python Features

- Use f-strings for string formatting (Python 3.6+)
- Use type hints for better code documentation (Python 3.5+)
- Use `pathlib` for file path operations
- Leverage `enum.Enum` for enumerations
- Use `typing` module for complex type annotations
- Consider `match`/`case` for structural pattern matching (Python 3.10+)

### Pythonic Idioms

- Use `is` for None comparisons, not `==`
- Use `in` for membership testing
- Use enumerate() instead of range(len())
- Use zip() to iterate over parallel sequences
- Use context managers for file operations
- Use list/dict/set comprehensions appropriately
- Avoid using `len()` to check if sequence is empty; use truthiness

## Code Quality

### Performance

- Use built-in functions and libraries (they're optimized)
- Use list comprehensions instead of loops for simple operations
- Use generators for large datasets to save memory
- Cache expensive function results with `@functools.lru_cache`
- Profile before optimizing (`cProfile`, `line_profiler`)
- Use appropriate data structures (set for membership, dict for lookups)

### Testing

- Write unit tests using `unittest` or `pytest`
- Follow AAA pattern (Arrange, Act, Assert)
- Use descriptive test names that describe the scenario
- Mock external dependencies
- Aim for high test coverage but focus on quality
- Use fixtures to reduce code duplication in tests

### Type Hints

- Use type hints for function parameters and return values
- Use `Optional[Type]` for values that can be None
- Use `Union[Type1, Type2]` for multiple possible types
- Use `List[Type]`, `Dict[KeyType, ValueType]` for collections
- Use `typing.Protocol` for structural subtyping
- Run mypy or similar type checkers

### Security

- Validate and sanitize all user input
- Use parameterized queries for database operations
- Don't store sensitive data in plain text
- Use secrets module for cryptographically strong random values
- Be cautious with `eval()`, `exec()`, and `pickle`
- Keep dependencies updated and scan for vulnerabilities

### Maintainability

- Keep modules and classes focused and cohesive
- Avoid deep nesting (max 3-4 levels)
- Use meaningful names that convey intent
- Remove unused imports and dead code
- Follow DRY (Don't Repeat Yourself) principle
- Use constants instead of magic numbers
- Refactor complex code into smaller functions

## Common Anti-Patterns to Avoid

- Using mutable default arguments
- Bare `except:` clauses
- Modifying a list while iterating over it
- Using global variables extensively
- String concatenation in loops (use `join()` instead)
- Not closing files (use context managers)
- Reinventing the wheel (use standard library)
- Premature optimization
- Overly complex list comprehensions (use loops for clarity)
- Using `range(len(sequence))` instead of direct iteration

## Tools and Linters

- **black**: Automatic code formatter
- **flake8**: Style guide enforcement
- **pylint**: Comprehensive code analysis
- **mypy**: Static type checker
- **isort**: Import statement organizer
- **bandit**: Security issue scanner

