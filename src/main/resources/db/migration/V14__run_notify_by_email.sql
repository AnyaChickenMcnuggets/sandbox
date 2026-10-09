-- Нужно ли отправить письмо о завершении прогона: пользователь выбирает это при запуске, а письмо
-- уходит позже, когда прогон закончится, поэтому выбор хранится в самом прогоне.
ALTER TABLE scenario_run ADD COLUMN notify_by_email BOOLEAN NOT NULL DEFAULT FALSE;
