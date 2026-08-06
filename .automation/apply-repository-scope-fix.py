from __future__ import annotations

import base64
import gzip
import subprocess
from pathlib import Path

PATCH = "H4sIAO+odGoC/71V30/bMBB+71/hx1SljvbAhCpgLePH0AZDlD1NPLjJpXg4dmRfyiLE/z47iZM2TTseplmV7Nrn7767+3wZj8eEhZHSEMYMWWh0FKaMy/AXW7n9NOQZrkL7a200ZMpwVLoI7/2Sg6HPOBiNRmTx7+CmUzI++nB08JGM3HR4SKbTAfFjlmX3YHKBdJ5HERgTQJphccOyYDiszN4GZDByi0zzFUMgSS6JWhjQK7hjGiQy8fmJSQniyh4Hwwm5FOrluOfslLxWUG5owFxLEimJ8BupAUQul+bcxjO39IG6yGjKMvJqXUNiyPi0ve1Gn/dNCzdAsoWAmJxUKD/ntaOvUBia1RAXldEj+TQhCRMGDraBnngMs9jmqvZn9kN+6ZqX4KjzPuyFUNEzxBbgRenYIXeCu+QCQdMYIhWDN+uJtirUTlJnm34etwGGm1trf9+qpZ029WByk4GMS11EuXaOduiiZ7tHEfu0RROuDQbDHVQchdr+QiLHgnJTh3xWeMBgaU17yViKZ0oJYLKHVn9BGvxOLSRLwVbRTZ1yL7XKs9vquFl3bHC1vHaKLefufRfoSTm1B8N31IbLJ9Acr4RaMHHJVrZFIJggquU5Id+4weON9J0O1xPBE9JY28AvXKewbaJOkHftxooJ+4wqDzXgdalqv3nOFF0Ctiw6sqvfPUca+etef57JNv77OV3H4OKzLdJyqj04SgmXVigWqge9h+GDCtIcXeewj+174vy+eritbuXGZu5rFgV9hsInllYl9yi0FFDzz6AGlv7QokNmR2raMP+emlodZaP0Re5EnJSS/28hWkr9/W07vJ3ZWNPSPtKebqX6oNVckwvKLWMWxxDP0O7OC4OQ0rrdPfAUbrgQ3Liv5r7KNFm2BblV2NRk/V3kme1/OBOitV573mTn804zpV3nNRDfCVZYNhi0X/qs3nLdZkLmqO234aA91uzFX2pP/wBPIYGl2ggAAA=="


def main() -> None:
    patch_path = Path(".automation/repository-scope-fix.patch")
    patch_path.write_bytes(gzip.decompress(base64.b64decode(PATCH)))
    try:
        subprocess.run(
            ["git", "apply", "--3way", "--whitespace=fix", str(patch_path)],
            check=True,
        )
    finally:
        patch_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
