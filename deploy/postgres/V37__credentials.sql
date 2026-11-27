-- The secret store's fourth kind: a verifier, what a node checks a calling system's value against, never the value itself.
alter table secrets drop constraint secrets_kind_check;
alter table secrets add constraint secrets_kind_check check (kind in ('value', 'credentials', 'key-pair', 'verifier'));

-- A credential joins the objects of the configuration: its key is its system's key and its own name, as a connection's is.
alter table config_objects drop constraint config_objects_kind_check;
alter table config_objects add constraint config_objects_kind_check check (kind in ('connection', 'system', 'credential'));
alter table config_objects drop constraint config_objects_check;
alter table config_objects add constraint config_objects_key_check check (
    kind in ('connection', 'credential') and key ~ '^(?!(rail8|subscribers|by-agreement)/)[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63}){1,2}$'
    or kind = 'system' and key ~ '^(?!(rail8|subscribers|by-agreement)(/|$))[a-z][a-z0-9-]{0,63}(/[a-z][a-z0-9-]{0,63})?$');
