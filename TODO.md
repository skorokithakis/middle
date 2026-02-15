# TODO items

Work on these one at a time. Mark them as done when done:

[ ] If the BT transfer gets interrupted, the ring never sleeps. That definitely needs to be fixed.
[ ] Try to find ways to increase BT download speed.
[ ] Add high-side P-MOS with a 1kΩ resistor to cut the mic's power.
[ ] See why the pendant never seems to sleep. Review every code path and make sure they all lead to sleep at the end.
[ ] Implement some sort of security or encryption on both ends, probably with a preshared key.
