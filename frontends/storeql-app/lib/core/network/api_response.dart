// Mirrors the backend ApiResponse<T> envelope:
// { "data": ..., "error": { "code", "message" }, "meta": { "requestId", "nextCursor" } }

class ApiEnvelope<T> {
  final T? data;
  final ApiError? error;
  final ApiMeta? meta;

  const ApiEnvelope({this.data, this.error, this.meta});

  factory ApiEnvelope.fromJson(
    Map<String, dynamic> json,
    T Function(Object?) fromData,
  ) {
    return ApiEnvelope(
      data: json['data'] != null ? fromData(json['data']) : null,
      error: json['error'] != null
          ? ApiError.fromJson(json['error'] as Map<String, dynamic>)
          : null,
      meta: json['meta'] != null
          ? ApiMeta.fromJson(json['meta'] as Map<String, dynamic>)
          : null,
    );
  }
}

class ApiError {
  final String code;
  final String message;

  /// What else the server said, as `name=value` strings — e.g. `slug=acme` on an
  /// SSO_REQUIRED refusal, naming the business's sign-in.
  final List<String> details;

  const ApiError({required this.code, required this.message, this.details = const []});

  factory ApiError.fromJson(Map<String, dynamic> json) => ApiError(
        code: json['code'] as String? ?? 'UNKNOWN',
        message: json['message'] as String? ?? 'An unexpected error occurred.',
        details: [for (final d in json['details'] as List<dynamic>? ?? const []) d.toString()],
      );

  /// The value of a `name=value` detail, or null.
  String? detail(String name) {
    for (final d in details) {
      if (d.startsWith('$name=')) return d.substring(name.length + 1);
    }
    return null;
  }

  @override
  String toString() => '[$code] $message';
}

class ApiMeta {
  final String? requestId;
  final String? nextCursor;

  const ApiMeta({this.requestId, this.nextCursor});

  factory ApiMeta.fromJson(Map<String, dynamic> json) => ApiMeta(
        requestId: json['requestId'] as String?,
        nextCursor: json['nextCursor'] as String?,
      );
}
