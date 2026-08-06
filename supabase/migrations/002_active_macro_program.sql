-- Macro+: one active goal per user.
-- The client keeps the active goal durable and links weekly check-ins to it.
-- Existing duplicate active rows must be reconciled before applying this
-- migration; silently deleting a user's goals would violate provenance.

create unique index if not exists macro_programs_one_active_per_user_uidx
    on public.macro_programs (user_id)
    where status = 'active';
