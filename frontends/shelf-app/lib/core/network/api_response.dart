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

  const ApiError({required this.code, required this.message});

  factory ApiError.fromJson(Map<String, dynamic> json) => ApiError(
        code: json['code'] as String? ?? 'UNKNOWN',
        message: json['message'] as String? ?? 'An unexpected error occurred.',
      );

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
