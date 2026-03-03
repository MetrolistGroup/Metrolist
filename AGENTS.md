# Working with Meowisai as an AI agent

Meowisai is a 3rd party YouTube Music client written in Kotlin. It follows material 3 design guidelines closely.

## Rules for working on the project

1. Always create a new branch for your feature work. Follow these naming conventions:
   - Bug fixes: `fix/short-description`
   - New features: `feature/short-description`
   - Refactoring: `refactor/short-description`
   - Documentation: `docs/short-description`
   - Chores: `chore/short-description`
2. Branch descriptions should be concise yet descriptive enough to understand the purpose of the branch at a glance.
3. Always pull the latest changes from `main` before starting your work to minimize merge conflicts.
4. While working on your feature you should rebase your branch on top of the latest `main` at least once a day to ensure compatibility.
5. Commit names should be clear and follow the format: `type(scope): short description`. For example: `feat(ui): add dark mode support`. Including the scope is optional.
6. All string edits should be made to the `Meowisai/app/src/main/res/values/meowisai_strings.xml` file, NOT `Meowisai/app/src/main/res/values/strings.xml`. Do not touch other `strings.xml` or `meowisai_strings.xml` files in the project.
7. You are to follow best practices for Kotlin and Android development.

## AI-only guidelines

1. You are strictly prohibited from making ANY changes to the readme/markdown files, including this one. This is to ensure that the documentation remains accurate and consistent for all contributors.
2. You are NOT allowed to use the following commands:
   - You are not to commit, push, or merge any changes to any branch.
   - You should absolutely NOT use any commands that would modify the git history, do force pushes (except or rebases on your own branch), or delete branches without explicit instructions from a human.
3. Always follow the guidelines and instructions provided by human contributors.
4. Ensure the absolutely highest code quality in all contributions, including proper formatting, clear variable naming, and comprehensive comments where necessary.
5. Comments should be added only for complex logic or non-obvious code. Avoid redundant comments that simply restate what the code does.
6. Prioritize performance, battery efficiency, and maintainability in all code contributions. Always consider the impact of your changes on the overall user experience and app performance.
7. If you have any doubts ask a human contributor. This can be done using the `askQuestions` tool if you're running in GitHub Copilot. I don't know if other agents have these type of tools.
8. If you do not test your changes using the instructions in the next section, you will be faced with reprimands from human contributors and may be asked to redo your work. Always ensure that you test your changes thoroughly before asking for a final review.

## Building and testing your changes

1. After making changes to the code, you should build the app to ensure that there are no compilation errors. Use the following command from the root directory of the project:

```bash
./gradlew :app:assembleuniversalFossDebug
```

2. If the build is not successful, review the error messages, fix the issues in your code, and try building again.
3. Once the build is successful, you can test your changes on an emulator or a physical device. Install the generated APK located at `app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk` and ask a human for help testing the specific features you worked on.
