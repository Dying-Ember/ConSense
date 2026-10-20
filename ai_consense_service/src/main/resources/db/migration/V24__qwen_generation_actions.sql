CREATE TABLE qwen_generation_action (
    id VARCHAR(128) PRIMARY KEY,
    revision BIGINT,
    identity_sha256 VARCHAR(64) NOT NULL,
    max_preflights INT NOT NULL,
    max_answers INT NOT NULL,
    max_total INT NOT NULL,
    lifetime_ms BIGINT NOT NULL,
    deadline_epoch_ms BIGINT NOT NULL,
    preflights INT NOT NULL,
    answers INT NOT NULL,
    total INT NOT NULL,
    reservations_json LONGTEXT NOT NULL
);
