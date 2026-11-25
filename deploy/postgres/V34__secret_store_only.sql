-- The secret store is the only source of a secret: a node no longer names its RAIL8_SECRET_* variables in its row, and the
-- uses of every deployment are recorded anew at its next deploy or restore, checked against the store alone.
alter table nodes drop column secret_variables;
update deployments set secret_uses_known = false;
