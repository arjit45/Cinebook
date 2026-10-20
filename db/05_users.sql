-- ============================================================
--  CineBook  --  File 5 of 5 : APPLICATION USER AND GRANTS
--  Run ONCE, as root, AFTER 00_setup.sql.
--
--  This file is kept out of 00_setup.sql on purpose. Setup drops
--  and rebuilds the database; it must never touch the MySQL
--  account the application signs in with, or a rebuild would
--  leave the app unable to connect.
--
--  What the app gets:
--      DML on cinebook.*           -- read and write rows
--      EXECUTE on ONE procedure    -- the only routine it calls
--  What it does NOT get:
--      CREATE / ALTER / DROP       -- no DDL, so no schema change
--      CREATE ROUTINE / TRIGGER    -- cannot plant code that runs
--                                     with higher privileges later
--      GRANT OPTION                -- cannot pass its rights on
--      FILE                        -- cannot read or write the disk
--
--  EXECUTE is not DDL: CALL sp_confirm_booking is an ordinary data
--  operation, and without this grant the confirm button fails with
--  a privilege error. It is granted on that one procedure, by name,
--  not on the whole schema.
-- ============================================================

-- Replace CHANGE_ME with the password you put in
-- src/main/resources/app.properties. That file is gitignored; this
-- one is not, so the value here stays a placeholder: the two must
-- match, and only one of them is allowed to be committed.
DROP USER IF EXISTS 'cinebook_app'@'localhost';

CREATE USER 'cinebook_app'@'localhost' IDENTIFIED BY 'CHANGE_ME';

GRANT SELECT, INSERT, UPDATE, DELETE
    ON cinebook.* TO 'cinebook_app'@'localhost';

GRANT EXECUTE
    ON PROCEDURE cinebook.sp_confirm_booking TO 'cinebook_app'@'localhost';

FLUSH PRIVILEGES;

-- Expect exactly three lines, and read them carefully:
--
--   GRANT USAGE ON *.*            <- not a privilege. MySQL writes
--                                    this for every existing account;
--                                    it means "no rights at this level".
--   GRANT SELECT, INSERT, UPDATE, DELETE ON cinebook.*
--   GRANT EXECUTE ON PROCEDURE cinebook.sp_confirm_booking
--
-- Anything else -- a fourth line, or CREATE/DROP/ALL in the second --
-- means the account has more rights than this section claims.
SHOW GRANTS FOR 'cinebook_app'@'localhost';

-- Prove the missing right, in the viva, on purpose:
--
--     mysql -u cinebook_app -p cinebook -e "DROP TABLE ticket;"
--     ERROR 1142 (42000): DROP command denied to user
--                         'cinebook_app'@'localhost'
--
-- That error is the demonstration. The application cannot drop a
-- table even if its own code tells it to.
