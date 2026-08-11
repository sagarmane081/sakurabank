-- V5: seed demo users for local development and recruiter demonstrations

INSERT INTO core.users (id, username, password_hash, role)
VALUES
    ('11111111-1111-1111-1111-111111111111',
     'customer',
     '$2a$10$/AIuq/ziHOXLN2ygwySa3OABN4Y64qSmxLWH2YP8ewEMNhrrKd/a6',
     'CUSTOMER'),

    ('22222222-2222-2222-2222-222222222222',
     'admin',
     '$2a$10$8QmiZ1nEIaJRywbwWfTh2eE7cIH1yBV87vr1Ph99GoumWNvwcso8y',
     'ADMIN'),

    ('33333333-3333-3333-3333-333333333333',
     'compliance',
     '$2a$10$2Pgs/ptPYvHX7RF/4vpm5emzu.lYBpLYdg5MGD0rBipBfVBt4hDiO',
     'COMPLIANCE_OFFICER');