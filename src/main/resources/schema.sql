CREATE TABLE IF NOT EXISTS currency_rate (
    requested_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    name VARCHAR(120) NOT NULL,
    effective_date DATE NOT NULL,
    previous_effective_date DATE NOT NULL,
    rate DECIMAL(24, 10) NOT NULL CHECK (rate > 0),
    previous_rate DECIMAL(24, 10) NOT NULL CHECK (previous_rate > 0),
    change_amount DECIMAL(24, 10) NOT NULL,
    change_percent DECIMAL(24, 6) NOT NULL,
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (requested_date, currency)
);
