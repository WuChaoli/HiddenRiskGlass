## ADDED Requirements

### Requirement:  detection parses JSON response
The system SHALL send a plain HTTP POST to `/ai/auto` for  item hazard detection and parse the JSON response body, replacing the previous SSE event stream.

#### Scenario: Detection returns content=true with inference results
- **WHEN** the server responds with HTTP 200 and JSON body `{"code":0,"content":true,"inference_result":[{...}]}`
- **THEN** the system SHALL report `hasHazard = true` to the callback

#### Scenario: Detection returns content=false
- **WHEN** the server responds with HTTP 200 and JSON body containing `content: false`
- **THEN** the system SHALL report `hasHazard = false` to the callback

#### Scenario: Detection returns content=true but empty inference_result
- **WHEN** the server responds with HTTP 200 and JSON body containing `content: true` and `inference_result: []` or `inference_result: null`
- **THEN** the system SHALL report `hasHazard = false` to the callback

#### Scenario: Server returns non-200 HTTP status
- **WHEN** the server responds with HTTP 4xx or 5xx
- **THEN** the system SHALL report failure via the `onFailure` callback

#### Scenario: Request is canceled before response
- **WHEN** `RequestHandle.cancel()` is called before the HTTP response arrives
- **THEN** the system SHALL NOT invoke any callback for that request

### Requirement: inference_result data is preserved
The system SHALL serialize the `inference_result` array to JSON and pass it as the `rawText` parameter to `DetectCallback.onSuccess`.

#### Scenario: inference_result contains labeled detection
- **WHEN** the server returns `inference_result` with items containing `label`, `bbox`, `score`, `area_r`, `inter`
- **THEN** the rawText passed to callback SHALL be a valid JSON array string of those items

### Requirement:  and  paths remain SSE
The system SHALL continue to use SSE `EventSource` for `requestDeepAnalysis` () and `fetchInspectionGuide` () requests.

#### Scenario: Deep analysis still uses SSE
- **WHEN** `requestDeepAnalysis` is called
- **THEN** the system SHALL use `openStream()` with SSE `EventSource`, not plain HTTP POST
