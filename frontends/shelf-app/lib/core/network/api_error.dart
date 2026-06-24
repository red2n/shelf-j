// Central handling for the backend's structured error envelope.
//
// Every Shelf-J endpoint returns `{ data, error: { code, message }, meta }` and
// uses stable machine codes (e.g. `INVENTORY_INSUFFICIENT_STOCK`) with a
// human-safe `message`. Screens used to string-match the HTTP status
// (`e.toString().contains('404')`) and throw the real contract away; these
// helpers read it instead so the UI can show the server's message and branch on
// the code.

import 'package:dio/dio.dart';

import 'api_response.dart';

/// The structured `{ code, message }` the backend returned for [error], or null
/// when the failure carries no envelope (network error, non-Dio throw).
ApiError? apiErrorOf(Object error) {
  if (error is DioException) {
    final data = error.response?.data;
    if (data is Map && data['error'] is Map) {
      return ApiError.fromJson(Map<String, dynamic>.from(data['error'] as Map));
    }
  }
  return null;
}

/// The stable machine error code (e.g. `INVENTORY_INSUFFICIENT_STOCK`), or null.
String? apiErrorCode(Object error) => apiErrorOf(error)?.code;

/// A user-facing message for any thrown [error]. Prefers the backend's structured
/// `message` (already written to be safe to show), then a friendly network-error
/// line, then [fallback]. Never surfaces a raw `DioException`/stack string.
String friendlyError(
  Object error, {
  String fallback = 'Something went wrong. Please try again.',
}) {
  final apiErr = apiErrorOf(error);
  if (apiErr != null && apiErr.message.isNotEmpty) {
    return apiErr.message;
  }
  if (error is DioException) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.connectionError:
        return "Can't reach the server. Check your connection and try again.";
      default:
        break;
    }
  }
  return fallback;
}
