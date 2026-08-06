-- Macro+: one derived expenditure estimate per user and window end date.
--
-- The app recomputes today's estimate when the Coach screen resumes. These
-- rows are derived state, not user-entered history, so a same-day recompute
-- must replace the existing row atomically rather than delete then insert.
-- Reconcile duplicate (user_id, window_end) rows in an existing database
-- before applying this migration; this migration intentionally does not guess
-- which derived row should win.

create unique index if not exists expenditure_estimates_user_window_end_uidx
    on public.expenditure_estimates (user_id, window_end);
