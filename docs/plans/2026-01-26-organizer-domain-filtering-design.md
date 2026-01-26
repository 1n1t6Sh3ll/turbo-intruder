# Organizer Domain Filtering

## Overview

Add domain filtering and pagination to the organizer items MCP resource.

## API

```
turbo://organizer?domain=example.com           # page 1
turbo://organizer?domain=example.com&page=2    # page 2
```

## Behavior

- `domain` parameter: exact match on host
- When filtering: paginated (10 per page), sorted by timestamp desc (nulls last), then ID desc
- Without filter: unchanged (returns all IDs, no pagination)

## Response Format (when filtered)

```json
{
  "count": 47,
  "page": 1,
  "page_size": 10,
  "total_pages": 5,
  "items": [{"id": 45}, {"id": 42}, ...]
}
```

## Implementation

1. Add `timeRequestSent: ZonedDateTime?` to `OrganizerItemData`
2. Add `getItemsByDomain(domain: String, page: Int, pageSize: Int)` to `OrganizerProvider`
3. Update `BurpOrganizerProvider` to populate timestamp and implement domain filtering with sort/pagination
4. Update `McpResourceHandlers.listOrganizerItems()` to parse `domain` and `page` params
