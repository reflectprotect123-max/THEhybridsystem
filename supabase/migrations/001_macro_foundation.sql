-- Macro-style nutrition foundation for Supabase/Postgres.
--
-- This migration keeps the original foods table columns and adds the tables
-- needed for fast logging, recipes, custom foods, weight trends, expenditure
-- estimates, macro programs, weekly check-ins, micronutrients, and body
-- metrics.  The exact proprietary MacroFactor parameters are not reproduced;
-- the deterministic engine in adaptive_engine.py is an explicit reference
-- implementation with configurable constants.

create extension if not exists pgcrypto;

create table if not exists public.foods (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    brand text,
    barcode text,
    serving_qty numeric not null default 100,
    serving_unit text not null default 'g',
    calories numeric not null default 0,
    protein_g numeric not null default 0,
    carbs_g numeric not null default 0,
    fat_g numeric not null default 0,
    source text not null default 'custom',
    external_id text,
    nutrition_basis_qty numeric not null default 100,
    nutrition_basis_unit text not null default 'g',
    serving_size_text text,
    nutrients jsonb not null default '{}'::jsonb,
    ingredients_text text,
    allergens text[],
    categories text[],
    countries text[],
    image_url text,
    source_url text,
    source_updated_at timestamptz,
    data_quality text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Upgrade the prototype foods table in place when it already exists.
alter table public.foods add column if not exists id uuid;
alter table public.foods add column if not exists nutrition_basis_qty numeric;
alter table public.foods add column if not exists nutrition_basis_unit text;
alter table public.foods add column if not exists serving_size_text text;
alter table public.foods add column if not exists nutrients jsonb;
alter table public.foods add column if not exists ingredients_text text;
alter table public.foods add column if not exists allergens text[];
alter table public.foods add column if not exists categories text[];
alter table public.foods add column if not exists countries text[];
alter table public.foods add column if not exists image_url text;
alter table public.foods add column if not exists source_url text;
alter table public.foods add column if not exists source_updated_at timestamptz;
alter table public.foods add column if not exists data_quality text;
alter table public.foods add column if not exists created_at timestamptz;
alter table public.foods add column if not exists updated_at timestamptz;

update public.foods set id = gen_random_uuid() where id is null;
update public.foods set nutrition_basis_qty = 100 where nutrition_basis_qty is null;
update public.foods set nutrition_basis_unit = serving_unit where nutrition_basis_unit is null;
update public.foods set nutrients = '{}'::jsonb where nutrients is null;
update public.foods set created_at = now() where created_at is null;
update public.foods set updated_at = now() where updated_at is null;
alter table public.foods alter column id set default gen_random_uuid();
alter table public.foods alter column id set not null;
alter table public.foods alter column nutrition_basis_qty set default 100;
alter table public.foods alter column nutrition_basis_unit set default 'g';
alter table public.foods alter column nutrients set default '{}'::jsonb;
alter table public.foods alter column created_at set default now();
alter table public.foods alter column updated_at set default now();

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'public.foods'::regclass and conname = 'foods_id_key'
    ) then
        alter table public.foods add constraint foods_id_key unique (id);
    end if;
end $$;

create unique index if not exists foods_source_external_id_uidx
    on public.foods (source, external_id)
    where source is not null and external_id is not null;
create index if not exists foods_barcode_idx on public.foods (barcode) where barcode is not null;
create index if not exists foods_name_search_idx on public.foods (lower(name));
create index if not exists foods_brand_search_idx on public.foods (lower(brand)) where brand is not null;

create table if not exists public.food_servings (
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references public.foods(id) on delete cascade,
    label text not null,
    quantity numeric not null,
    unit text not null,
    grams numeric,
    millilitres numeric,
    is_default boolean not null default false,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    unique (food_id, label)
);

create table if not exists public.nutrition_profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    sex text check (sex in ('female', 'male', 'other', 'unspecified')),
    birth_date date,
    height_cm numeric check (height_cm is null or height_cm between 50 and 260),
    current_weight_kg numeric check (current_weight_kg is null or current_weight_kg between 20 and 500),
    body_fat_pct numeric check (body_fat_pct is null or body_fat_pct between 2 and 70),
    activity_level text not null default 'moderate',
    timezone text not null default 'Australia/Sydney',
    unit_system text not null default 'metric' check (unit_system in ('metric', 'imperial')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.custom_foods (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    name text not null,
    brand text,
    barcode text,
    serving_qty numeric not null default 100,
    serving_unit text not null default 'g',
    calories numeric not null default 0,
    protein_g numeric not null default 0,
    carbs_g numeric not null default 0,
    fat_g numeric not null default 0,
    nutrients jsonb not null default '{}'::jsonb,
    ingredients_text text,
    allergens text[],
    source text not null default 'user_custom',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists custom_foods_user_name_idx on public.custom_foods (user_id, lower(name));
create index if not exists custom_foods_barcode_idx on public.custom_foods (user_id, barcode) where barcode is not null;

create table if not exists public.recipes (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    name text not null,
    description text,
    instructions text,
    servings numeric not null default 1 check (servings > 0),
    image_url text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.recipe_items (
    id uuid primary key default gen_random_uuid(),
    recipe_id uuid not null references public.recipes(id) on delete cascade,
    food_id uuid references public.foods(id) on delete restrict,
    custom_food_id uuid references public.custom_foods(id) on delete restrict,
    quantity numeric not null check (quantity > 0),
    unit text not null default 'g',
    sort_order integer not null default 0,
    check ((food_id is not null) <> (custom_food_id is not null))
);
create index if not exists recipe_items_recipe_idx on public.recipe_items (recipe_id, sort_order);

create table if not exists public.food_log_entries (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    log_date date not null,
    meal text not null default 'other',
    entry_kind text not null check (entry_kind in ('food', 'custom_food', 'recipe', 'quick_add')),
    food_id uuid references public.foods(id) on delete restrict,
    custom_food_id uuid references public.custom_foods(id) on delete restrict,
    recipe_id uuid references public.recipes(id) on delete restrict,
    quantity numeric not null default 1 check (quantity > 0),
    unit text not null default 'serving',
    calories numeric not null default 0,
    protein_g numeric not null default 0,
    carbs_g numeric not null default 0,
    fat_g numeric not null default 0,
    nutrients jsonb not null default '{}'::jsonb,
    display_name text not null,
    source_snapshot jsonb not null default '{}'::jsonb,
    notes text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    check (
        (entry_kind = 'food' and food_id is not null and custom_food_id is null and recipe_id is null)
        or (entry_kind = 'custom_food' and food_id is null and custom_food_id is not null and recipe_id is null)
        or (entry_kind = 'recipe' and food_id is null and custom_food_id is null and recipe_id is not null)
        or (entry_kind = 'quick_add' and food_id is null and custom_food_id is null and recipe_id is null)
    )
);
create index if not exists food_log_user_date_idx on public.food_log_entries (user_id, log_date);
create index if not exists food_log_user_created_idx on public.food_log_entries (user_id, created_at desc);

-- Explicit day state prevents the expenditure engine from treating an
-- unlogged day as a normal low-calorie day.  'fasted' is a user declaration,
-- not an inference from missing entries.
create table if not exists public.daily_log_status (
    user_id uuid not null references auth.users(id) on delete cascade,
    log_date date not null,
    status text not null check (status in ('complete', 'partial', 'fasted', 'unlogged')),
    note text,
    updated_at timestamptz not null default now(),
    primary key (user_id, log_date)
);

create table if not exists public.weight_entries (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    measured_at timestamptz not null,
    weight_kg numeric not null check (weight_kg between 20 and 500),
    source text not null default 'manual',
    note text,
    created_at timestamptz not null default now()
);
create index if not exists weight_entries_user_time_idx on public.weight_entries (user_id, measured_at);

create table if not exists public.weight_trend_points (
    user_id uuid not null references auth.users(id) on delete cascade,
    trend_date date not null,
    trend_weight_kg numeric not null,
    method text not null default 'ewma_reference',
    source_window_days integer not null default 14,
    created_at timestamptz not null default now(),
    primary key (user_id, trend_date)
);

create table if not exists public.macro_programs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    name text not null default 'Macro program',
    mode text not null check (mode in ('coached', 'collaborative', 'manual')),
    goal text not null check (goal in ('lose', 'gain', 'maintain')),
    target_rate_kg_per_week numeric not null default 0,
    start_date date not null,
    end_date date,
    weekly_calorie_budget numeric,
    protein_preference text,
    fat_preference text,
    status text not null default 'active' check (status in ('draft', 'active', 'paused', 'completed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check ((goal = 'lose' and target_rate_kg_per_week <= 0)
        or (goal = 'gain' and target_rate_kg_per_week >= 0)
        or (goal = 'maintain' and target_rate_kg_per_week = 0))
);
create index if not exists macro_programs_user_status_idx on public.macro_programs (user_id, status);

create table if not exists public.macro_program_days (
    program_id uuid not null references public.macro_programs(id) on delete cascade,
    target_date date not null,
    calories numeric not null,
    protein_g numeric not null,
    carbs_g numeric not null,
    fat_g numeric not null,
    source text not null default 'engine',
    created_at timestamptz not null default now(),
    primary key (program_id, target_date)
);

create table if not exists public.expenditure_estimates (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    window_start date not null,
    window_end date not null,
    estimate_kcal numeric not null,
    previous_estimate_kcal numeric,
    raw_estimate_kcal numeric,
    trend_slope_kg_per_week numeric,
    nutrition_days integer not null default 0,
    weight_days integer not null default 0,
    confidence text not null check (confidence in ('holding', 'low', 'medium', 'high')),
    state text not null check (state in ('holding', 'updating')),
    method text not null default 'intake_minus_trend_energy',
    inputs jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists expenditure_user_created_idx on public.expenditure_estimates (user_id, created_at desc);

create table if not exists public.weekly_check_ins (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    program_id uuid references public.macro_programs(id) on delete set null,
    week_start date not null,
    week_end date not null,
    status text not null check (status in ('pending', 'held', 'accepted', 'declined')),
    previous_expenditure_kcal numeric,
    observed_expenditure_kcal numeric,
    proposed_expenditure_kcal numeric,
    proposed_calories numeric,
    proposed_protein_g numeric,
    proposed_carbs_g numeric,
    proposed_fat_g numeric,
    modules jsonb not null default '[]'::jsonb,
    explanation text not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    unique (user_id, week_start)
);

create table if not exists public.user_nutrient_targets (
    user_id uuid not null references auth.users(id) on delete cascade,
    nutrient_key text not null,
    target_amount numeric not null,
    unit text not null,
    target_type text not null default 'custom' check (target_type in ('system', 'custom')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, nutrient_key)
);

create table if not exists public.food_favorites (
    user_id uuid not null references auth.users(id) on delete cascade,
    food_id uuid references public.foods(id) on delete cascade,
    custom_food_id uuid references public.custom_foods(id) on delete cascade,
    recipe_id uuid references public.recipes(id) on delete cascade,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    check (((food_id is not null)::integer + (custom_food_id is not null)::integer + (recipe_id is not null)::integer) = 1)
);
create unique index if not exists food_favorites_food_uidx on public.food_favorites (user_id, food_id) where food_id is not null;
create unique index if not exists food_favorites_custom_uidx on public.food_favorites (user_id, custom_food_id) where custom_food_id is not null;
create unique index if not exists food_favorites_recipe_uidx on public.food_favorites (user_id, recipe_id) where recipe_id is not null;

create table if not exists public.body_measurements (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    measured_on date not null,
    waist_cm numeric,
    chest_cm numeric,
    hip_cm numeric,
    arm_cm numeric,
    thigh_cm numeric,
    note text,
    created_at timestamptz not null default now()
);

create table if not exists public.progress_photos (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    captured_on date not null,
    storage_path text not null,
    view_name text,
    note text,
    created_at timestamptz not null default now()
);

create or replace view public.daily_nutrition_totals
with (security_invoker = true)
as
select
    user_id,
    log_date,
    coalesce(sum(calories) filter (where deleted_at is null), 0) as calories,
    coalesce(sum(protein_g) filter (where deleted_at is null), 0) as protein_g,
    coalesce(sum(carbs_g) filter (where deleted_at is null), 0) as carbs_g,
    coalesce(sum(fat_g) filter (where deleted_at is null), 0) as fat_g,
    count(*) filter (where deleted_at is null) as entry_count
from public.food_log_entries
group by user_id, log_date;

-- Public catalog reads are allowed; writes should use a controlled import or
-- service role.  User-owned data is protected by auth.uid().
alter table public.foods enable row level security;
alter table public.food_servings enable row level security;
alter table public.nutrition_profiles enable row level security;
alter table public.custom_foods enable row level security;
alter table public.recipes enable row level security;
alter table public.recipe_items enable row level security;
alter table public.food_log_entries enable row level security;
alter table public.daily_log_status enable row level security;
alter table public.weight_entries enable row level security;
alter table public.weight_trend_points enable row level security;
alter table public.macro_programs enable row level security;
alter table public.macro_program_days enable row level security;
alter table public.expenditure_estimates enable row level security;
alter table public.weekly_check_ins enable row level security;
alter table public.user_nutrient_targets enable row level security;
alter table public.food_favorites enable row level security;
alter table public.body_measurements enable row level security;
alter table public.progress_photos enable row level security;

drop policy if exists "foods_read_authenticated" on public.foods;
create policy "foods_read_authenticated" on public.foods for select to authenticated using (true);
drop policy if exists "food_servings_read_authenticated" on public.food_servings;
create policy "food_servings_read_authenticated" on public.food_servings for select to authenticated using (true);

drop policy if exists "profile_owner_all" on public.nutrition_profiles;
create policy "profile_owner_all" on public.nutrition_profiles for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "custom_food_owner_all" on public.custom_foods;
create policy "custom_food_owner_all" on public.custom_foods for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "recipe_owner_all" on public.recipes;
create policy "recipe_owner_all" on public.recipes for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "recipe_item_owner_all" on public.recipe_items;
create policy "recipe_item_owner_all" on public.recipe_items for all to authenticated
    using (exists (select 1 from public.recipes r where r.id = recipe_items.recipe_id and r.user_id = auth.uid()))
    with check (exists (select 1 from public.recipes r where r.id = recipe_items.recipe_id and r.user_id = auth.uid()));
drop policy if exists "food_log_owner_all" on public.food_log_entries;
create policy "food_log_owner_all" on public.food_log_entries for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "day_status_owner_all" on public.daily_log_status;
create policy "day_status_owner_all" on public.daily_log_status for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "weight_owner_all" on public.weight_entries;
create policy "weight_owner_all" on public.weight_entries for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "trend_owner_all" on public.weight_trend_points;
create policy "trend_owner_all" on public.weight_trend_points for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "program_owner_all" on public.macro_programs;
create policy "program_owner_all" on public.macro_programs for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "program_day_owner_all" on public.macro_program_days;
create policy "program_day_owner_all" on public.macro_program_days for all to authenticated
    using (exists (select 1 from public.macro_programs p where p.id = macro_program_days.program_id and p.user_id = auth.uid()))
    with check (exists (select 1 from public.macro_programs p where p.id = macro_program_days.program_id and p.user_id = auth.uid()));
drop policy if exists "expenditure_owner_all" on public.expenditure_estimates;
create policy "expenditure_owner_all" on public.expenditure_estimates for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "checkin_owner_all" on public.weekly_check_ins;
create policy "checkin_owner_all" on public.weekly_check_ins for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "nutrient_target_owner_all" on public.user_nutrient_targets;
create policy "nutrient_target_owner_all" on public.user_nutrient_targets for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "favorite_owner_all" on public.food_favorites;
create policy "favorite_owner_all" on public.food_favorites for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "measurement_owner_all" on public.body_measurements;
create policy "measurement_owner_all" on public.body_measurements for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists "photo_owner_all" on public.progress_photos;
create policy "photo_owner_all" on public.progress_photos for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

