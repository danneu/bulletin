# bulletin

A bolt-on bulletin board system.

A recently started stubbing out a experimental forum system at [www.fed.nu](http://www.fed.nu), so that's what this is kinda gonna look like.

## Setting up for local development

Install leiningen so that you have the `lein` command available in your console.

Use environment variables to configure bulletin. A nice way to do this is to create `.lein-env` in the root directory:

    {:database-url "postgres://<dbuser>:<dbpassword>@localhost:5432/bulletin"}

You can also provide `:port <Number>`, but the default port will be 3000.

Now boot the server:

    lein ring server-headless

To make the server create the database tables with see data, visit `http://localhost:3000/reset-db`.

The webiste should now be running on `http://localhost:3000`.
