# Stop the Shelf-J Docker Compose stack

```bash
docker compose down $ARGUMENTS
```

`$ARGUMENTS` defaults to nothing (stops containers, keeps volumes). Pass `-v` to also remove volumes (wipes all data — warn the user before doing this). Pass a service name to stop just that one container.

After stopping, confirm with:
```bash
docker compose ps
```
