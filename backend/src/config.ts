import { z } from "zod";

const configSchema = z.object({
  GOOGLE_CLOUD_PROJECT: z.string().min(1).default("ethglobaltokyo2026lifelink"),
  PORT: z.coerce.number().int().positive().default(8080),
});

export const config = configSchema.parse(process.env);
