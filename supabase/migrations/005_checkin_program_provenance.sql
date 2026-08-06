-- Macro+: keep weekly check-ins distinct across macro-program changes.
--
-- The original table keyed a check-in by (user_id, week_start). That lets a
-- goal change during the same calendar week overwrite the prior program's
-- proposal when the next check-in is upserted. `program_id` is already the
-- provenance link; include it in the natural key so historical proposals
-- remain attached to the program that produced them.
--
-- Existing rows remain intact. The old unique constraint guaranteed there
-- cannot already be duplicate rows for the new key. Legacy rows with a NULL
-- program_id remain readable; current app writes always include a program ID.

alter table public.weekly_check_ins
    drop constraint if exists weekly_check_ins_user_id_week_start_key;

-- The base migration uses the name above, but an older prototype may have
-- recreated the same unique constraint under a different generated name.
-- Remove only a constraint whose definition is exactly the old natural key.
do $$
declare
    old_constraint text;
begin
    select conname
      into old_constraint
      from pg_constraint
     where conrelid = 'public.weekly_check_ins'::regclass
       and contype = 'u'
       and pg_get_constraintdef(oid) = 'UNIQUE (user_id, week_start)'
     limit 1;
    if old_constraint is not null then
        execute format('alter table public.weekly_check_ins drop constraint %I', old_constraint);
    end if;
end $$;

-- `program_id` is nullable, and Postgres treats NULLs as distinct in a unique
-- index by default. Without NULLS NOT DISTINCT, legacy or program-less rows
-- would get no uniqueness protection at all, and
-- `ON CONFLICT (user_id, program_id, week_start)` could never match one -- an
-- upsert with a null program_id would append a new row on every recompute
-- instead of updating the existing one. NULLS NOT DISTINCT (Postgres 15+,
-- supported by Supabase) makes a null program_id behave like any other value
-- for both the constraint and the upsert conflict target.
create unique index if not exists weekly_check_ins_user_program_week_uidx
    on public.weekly_check_ins (user_id, program_id, week_start)
    nulls not distinct;

-- Keep the provenance foreign key inside the authenticated user's account.
-- The base policy checked only weekly_check_ins.user_id, which left a
-- cross-owner program reference possible for a caller that guessed a UUID.
drop policy if exists "checkin_owner_all" on public.weekly_check_ins;
create policy "checkin_owner_all" on public.weekly_check_ins for all to authenticated
    using (user_id = auth.uid())
    with check (
        user_id = auth.uid()
        and (
            program_id is null
            or exists (
                select 1
                from public.macro_programs p
                where p.id = weekly_check_ins.program_id
                  and p.user_id = auth.uid()
            )
        )
    );
