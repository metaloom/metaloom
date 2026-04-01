# Permissions

```d2 layout=elk theme=200
direction: right


# ACL
u: User
g: Group
r: Role
p: Permission
t: AuthToken

r -> g
g <- u
p -> r: create tags
Permission -> r: create assets

t -> u

```