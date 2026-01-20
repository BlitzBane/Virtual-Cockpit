# Agents

This file documents the agents used by the Virtual-Cockpit repository: their responsibilities, configuration, and how to add or run agents locally and in CI.

## Overview

Agents are small processes or services that perform discrete tasks for the Virtual-Cockpit project, such as telemetry collection, simulation controllers, data exporters, or automated checks. Each agent should have a clear purpose, configuration, and tests.

## Existing agents

- example-agent
  - Purpose: Placeholder example agent used to demonstrate the agent template.
  - Entry point: `./agents/example-agent/main` (replace with actual path)
  - Config: `agents/example-agent/config.yaml`
  - Health check: `http://localhost:PORT/health` or equivalent

(Add entries for real agents in this section.)

## Agent template (how to add a new agent)

When adding a new agent, include the following in the repository:

- agents/<agent-name>/README.md — short description and usage examples
- agents/<agent-name>/main (or main.go / index.js / run.sh) — entry point
- agents/<agent-name>/config.example.yaml — example configuration
- agents/<agent-name>/Dockerfile — container specification (if applicable)
- agents/<agent-name>/tests/ — unit and integration tests

Template example for README.md:

```yaml
name: <agent-name>
purpose: Short description of what the agent does
entrypoint: ./main
config: config.example.yaml
health_endpoint: /health
maintainer: @your-github-handle
```

## Configuration

- Use `config.example.yaml` for example configuration and add sensitive values to environment variables or a CI secret store.
- Prefer 12-factor friendly configuration (env vars + file-based defaults).

## Running agents locally

- From repository root: `cd agents/<agent-name> && ./run.sh` or `go run main.go` or `npm start` depending on the implementation.
- Use the example config and override with environment variables for secrets.

## CI / Deployment

- Add a dedicated CI workflow for agents that require building or containerizing.
- Use integration tests that can run in ephemeral environments or use test doubles/mocks.

## Testing and validation

- Unit tests should run on every PR.
- Integration tests should be marked and run in scheduled or PR workflows as appropriate.

## Maintenance

- Maintain a `maintainers` section in the agent README with contact/owners.