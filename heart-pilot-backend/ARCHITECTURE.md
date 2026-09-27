# Heart Pilot Backend Architecture

This backend is organized by business capability. Each module owns its web, data,
and service layers, while cross-cutting code lives under `common`, `config`,
`security`, and `infrastructure`.

```text
com.heartpilot
├── common
│   ├── api
│   ├── entity
│   └── exception
├── config
├── infrastructure
│   ├── ai
│   │   └── tool
│   └── rag
├── module
│   ├── agent
│   ├── auth
│   ├── conversation
│   ├── file
│   ├── knowledge
│   ├── usage
│   └── user
└── security
```

Inside a business module, code is split into `controller`, `dto`, `entity`,
`repository`, and `service`. Business service contracts live in `service`, and
their matching `XxxServiceImpl` implementations live in `service/impl`, matching
the convention used by the VideoNest backend. Storage uses one shared
`StorageService` strategy contract with local and MinIO implementations.

Removed business capabilities are retired through additive Flyway migrations;
historical migrations remain immutable so existing databases keep valid checksums.
