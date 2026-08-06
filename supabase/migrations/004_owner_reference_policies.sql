-- Macro+: close cross-owner reference paths for user-owned records.
--
-- The base migration correctly scopes each row by its own user_id, but a row
-- containing a foreign key can still be used as a bridge to another user's
-- custom food or recipe unless the referenced owner is checked as well. These
-- policies keep the public food catalogue readable while ensuring that
-- custom-food and recipe references remain inside the authenticated user's
-- account. This migration is idempotent and does not delete or rewrite data.

drop policy if exists "recipe_item_owner_all" on public.recipe_items;
create policy "recipe_item_owner_all" on public.recipe_items for all to authenticated
    using (
        exists (
            select 1
            from public.recipes r
            where r.id = recipe_items.recipe_id
              and r.user_id = auth.uid()
        )
    )
    with check (
        exists (
            select 1
            from public.recipes r
            where r.id = recipe_items.recipe_id
              and r.user_id = auth.uid()
        )
        and (
            recipe_items.custom_food_id is null
            or exists (
                select 1
                from public.custom_foods cf
                where cf.id = recipe_items.custom_food_id
                  and cf.user_id = auth.uid()
            )
        )
    );

drop policy if exists "food_log_owner_all" on public.food_log_entries;
create policy "food_log_owner_all" on public.food_log_entries for all to authenticated
    using (user_id = auth.uid())
    with check (
        user_id = auth.uid()
        and (
            food_log_entries.custom_food_id is null
            or exists (
                select 1
                from public.custom_foods cf
                where cf.id = food_log_entries.custom_food_id
                  and cf.user_id = auth.uid()
            )
        )
        and (
            food_log_entries.recipe_id is null
            or exists (
                select 1
                from public.recipes r
                where r.id = food_log_entries.recipe_id
                  and r.user_id = auth.uid()
            )
        )
    );

drop policy if exists "favorite_owner_all" on public.food_favorites;
create policy "favorite_owner_all" on public.food_favorites for all to authenticated
    using (user_id = auth.uid())
    with check (
        user_id = auth.uid()
        and (
            food_favorites.custom_food_id is null
            or exists (
                select 1
                from public.custom_foods cf
                where cf.id = food_favorites.custom_food_id
                  and cf.user_id = auth.uid()
            )
        )
        and (
            food_favorites.recipe_id is null
            or exists (
                select 1
                from public.recipes r
                where r.id = food_favorites.recipe_id
                  and r.user_id = auth.uid()
            )
        )
    );
