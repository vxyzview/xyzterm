# Contributing

## Scope

xyzterm is deliberately minimal: a terminal running a proot Ubuntu environment, and nothing else.
Features that add editors, file managers, or extension marketplaces are out of scope — see the
[README](/README.md) for the project's design goals.

## Build the project

Please build the project and test your changes before contributing. You can find further information
in the [build instructions](/README.md).

## Source code formatting

- Indents: 4 spaces
- Kotlin: Use `ktfmtFormat` task in Gradle
- XML: Default Android Studio formatter with 4 spaces

> [!NOTE]
> To enforce formatting automatically on every commit, you can install the pre-commit hook once:
>
> ```bash
> git config core.hooksPath .githooks
> ```
>
> After that, `ktfmtFormat` will run automatically on every commit.

## Propose a change

We follow the GitHub Flow. To submit a pull request:

- Fork the repository.
- Clone your fork to your local machine.
- Create a new branch from `main` branch for your feature or fix.
- Commit your changes and push the branch to your fork.
- Open a pull request from your branch to the `main` branch of this repository.

Ensure your pull request includes:

- A clear description of the problem or feature.
- References to any related issues.
- Documentation updates.
