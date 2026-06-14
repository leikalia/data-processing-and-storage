class AppError(Exception):
    status_code = 400

    def __init__(self, message: str, *, extra: dict | None = None):
        self.message = message
        self.extra = extra or {}
        super().__init__(message)


class BadRequestError(AppError):
    status_code = 400


class NotFoundError(AppError):
    status_code = 404


class ConflictError(AppError):
    status_code = 409


class ValidationError(AppError):
    status_code = 422


class InternalServerError(AppError):
    status_code = 500


class ServiceUnavailableError(AppError):
    status_code = 503


class GatewayTimeoutError(AppError):
    status_code = 504
    