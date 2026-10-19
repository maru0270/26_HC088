"""BikeEyes server package initialization."""

from pathlib import Path

from dotenv import load_dotenv


# Local development secrets live only in server/.env. Existing process
# environment variables always take precedence.
load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=False)
