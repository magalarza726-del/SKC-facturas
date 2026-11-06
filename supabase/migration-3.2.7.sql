update public.skc_events
set payload = payload || '{"role":"ADMIN","transferEnabled":true}'::jsonb,
    updated_at = now()
where entity_type = 'users'
  and entity_id = 'administracion';

drop policy if exists "skc role read" on public.skc_events;

create policy "skc role read" on public.skc_events
for select to authenticated using (
  entity_type in ('users','catalogs','appConfig','ledger')
  or public.skc_is_admin()
  or (entity_type='transactions' and (payload->>'createdByUserId'=public.skc_current_profile_id() or payload->>'accountUserId'=public.skc_current_profile_id()))
  or (entity_type='messages' and (payload->>'senderUserId'=public.skc_current_profile_id() or payload->>'recipientUserId'=public.skc_current_profile_id()))
  or (entity_type='reminders' and payload->>'userId'=public.skc_current_profile_id())
);
