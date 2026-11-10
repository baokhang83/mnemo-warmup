# Training lease

When several replicas or releases start together, only one trainer should work on a given archive. Each
trainer tries to create a small lock object named after the fingerprint; creating it is all-or-nothing, so one
succeeds. The holder keeps renewing it while it trains and removes it when done. If a trainer crashes, its
lock stops being renewed and expires, and the next trainer takes it over. A trainer that finds the lock gone
while it was training does not publish.
