---
title: Currency converter
description: Converting between over 160 currencies, offline included.
---

# Currency converter

Type an amount, pick **From** and **To**, and the answer appears as you type. **Swap** turns the
pair around.

## Where the rates come from

The converter downloads a published table of exchange rates from
[ExchangeRate-API](https://www.exchangerate-api.com) (`open.er-api.com`): over 160 currencies, the
ruble and the Vietnamese dong among them. A converter can also be set up to use the European
Central Bank's table via `api.frankfurter.app`, which has fewer currencies.

The request asks for the **whole table** and carries nothing of yours — no amount, no currency you
picked. Which currencies you look at is never sent anywhere.

The line under the answer says whose rates they are, for which date, and whether they are **live**
or **cached, offline**.

## Offline

The last table the converter downloaded is kept on the phone. With no connection it uses that table
and says so. Only a phone that has never had a connection since install has nothing to convert
with; the converter then says *Offline, and nothing cached yet*.

## Settings

**Settings → Currency converter** sets the currency it starts from. The currencies you picked last
are remembered too.

## Rates are for reference

Published rates are mid-market reference rates. A bank or an exchange office will quote you
something else; use the converter to know roughly what a price is, not what a transfer will cost.
