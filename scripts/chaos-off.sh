#!/usr/bin/env bash
curl -s -X DELETE http://localhost:8081/__admin/mappings/1e2b1b9d-dbca-440d-ad47-b42009b7b375 > /dev/null
curl -s -X DELETE http://localhost:8081/__admin/mappings/6fd12b32-88b3-4d33-b4fe-4a326fe07f37 > /dev/null
echo "Caos removido. O circuito deve fechar apos o half-open (waitDuration 5s + 50 chamadas ok)."
