# Security policy

## Supported versions

Only the latest release line receives fixes. At the time of writing that is the 1.x line; the `1.1.0` release
will supersede it once published.

## Reporting a vulnerability

Do not open a public issue for a security problem.

Use GitHub's private vulnerability reporting for this repository (Security tab, "Report a vulnerability"). If that
is unavailable, contact the maintainers through the GitHub profile of the repository owner.

Include:

- the Hypso version (`sha256sum` of the jar, if available), the Geyser build, and the server platform;
- a description of the impact and the steps to reproduce;
- whether the issue can be triggered by a Bedrock or Java client without operator privileges.

We aim to acknowledge reports within a few days. Fixes are released as a new version, following the release
process in CONTRIBUTING.md.

## Scope

In scope: the Hypso extension code, its build and release workflows, and the released jar.

Out of scope: Geyser, mcprotocollib, Minecraft, and the Bedrock client. Report those to their own projects.
