export class AppError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number = 400,
    public readonly details?: unknown
  ) {
    super(message);
    this.name = 'AppError';
  }
}

export class NotFoundError extends AppError {
  constructor(resource: string) {
    super('NOT_FOUND', `${resource} not found`, 404);
  }
}

export class ValidationError extends AppError {
  constructor(message: string, details?: unknown) {
    super('VALIDATION_FAILED', message, 422, details);
  }
}

export class IdempotencyConflictError extends AppError {
  constructor() {
    super('IDEMPOTENCY_CONFLICT', 'The same idempotency key was used with a different payload', 409);
  }
}

export class MealRevisionConflictError extends AppError {
  constructor(public readonly currentRevision: number) {
    super('MEAL_REVISION_CONFLICT', 'base_revision is stale', 409, { currentRevision });
  }
}

export class AiInvalidOutputError extends AppError {
  constructor(message: string, details?: unknown) {
    super('AI_INVALID_OUTPUT', message, 502, details);
  }
}

export class AiUnavailableError extends AppError {
  constructor(message = 'AI service unavailable') {
    super('AI_UNAVAILABLE', message, 503);
  }
}
