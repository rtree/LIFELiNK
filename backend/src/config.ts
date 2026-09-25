import { z } from "zod";

const trimmedSecret = (schema: z.ZodString) =>
  z.preprocess(
    (value) => (typeof value === "string" ? value.trim() : value),
    schema,
  );

const configSchema = z.object({
  BACKEND_URL: z.url().default(
    "https://lifelink-backend-1023311564471.asia-northeast1.run.app",
  ),
  GOOGLE_CLOUD_PROJECT: z.string().min(1).default("ethglobaltokyo2026lifelink"),
  OPENAI_API_KEY: trimmedSecret(z.string().min(1)).optional(),
  OPENAI_REALTIME_MODEL: z.string().min(1).default("gpt-realtime"),
  PORT: z.coerce.number().int().positive().default(8080),
  TWILIO_ACCOUNT_SID: trimmedSecret(z.string().startsWith("AC")).optional(),
  TWILIO_AUTH_TOKEN: trimmedSecret(z.string().min(1)).optional(),
  TWILIO_FROM_NUMBER: trimmedSecret(
    z.string().regex(/^\+[1-9]\d{7,14}$/),
  ).optional(),
});

export const config = configSchema.parse(process.env);
