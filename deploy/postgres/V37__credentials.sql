-- The secret store's fourth kind: a verifier, what a node checks a calling system's value against, never the value itself.
alter table secrets drop constraint secrets_kind_check;
alter table secrets add constraint secrets_kind_check check (kind in ('value', 'credentials', 'key-pair', 'verifier'));
